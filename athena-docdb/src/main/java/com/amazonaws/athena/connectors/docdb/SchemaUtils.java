/*-
 * #%L
 * athena-mongodb
 * %%
 * Copyright (C) 2019 Amazon Web Services
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package com.amazonaws.athena.connectors.docdb;

import com.amazonaws.athena.connector.lambda.data.FieldBuilder;
import com.amazonaws.athena.connector.lambda.data.SchemaBuilder;
import com.amazonaws.athena.connector.lambda.domain.TableName;
import com.mongodb.DBRef;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import org.apache.arrow.vector.types.Types;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.bson.BsonTimestamp;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * Collection of helpful utilities that handle DocumentDB schema inference, type, and naming conversion.
 * <p>
 * Inferred Schemas are formed by scanning N documents from the desired collection and then performing a union
 * of all fields in those Documents. The same union approach is applied to complex types (structs aka nested Documents).
 * If a type mistmatch is discovered, we assume the type is VARCHAR since most types can be coerced to VARCHAR. However,
 * this naive coercion does not work well if you then try to filter on the coerced field because whifen we push the filter
 * into DocDB it will almost certainly result in no matches.
 */
public class SchemaUtils
{
    private static final Logger logger = LoggerFactory.getLogger(SchemaUtils.class);

    private SchemaUtils() {}

    /**
     * This method will produce an Apache Arrow Schema for the given TableName and DocumentDB connection
     * by scanning up to the requested number of rows and using basic schema inference to determine
     * data types.
     *
     * @param client The DocumentDB connection to use for the scan operation.
     * @param table The DocumentDB TableName for which to produce an Apache Arrow Schema.
     * @param numObjToSample The number of records to scan as part of producing the Schema.
     * @return An Apache Arrow Schema representing the schema of the HBase table.
     * @note The resulting schema is a union of the schema of every row that is scanned. Presently the code does not
     * attempt to resolve conflicts if unique field has different types across documents. It is recommend that you
     * use AWS Glue to define a schema for tables which may have such conflicts. In the future we may enhance this method
     * to use a reasonable default (like String) and coerce heterogeneous fields to avoid query failure but forcing
     * explicit handling by defining Schema in AWS Glue is likely a better approach.
     */
    public static Schema inferSchema(MongoDatabase db, TableName table, int numObjToSample)
    {
        return inferSchema(db, table, numObjToSample, 0, null);
    }

    /**
     * BAH fork: samples the collection in two slices and unions the result.
     * <p>
     * The base slice is the unsorted scan the upstream connector has always used. It returns the same
     * documents on every cold start, which keeps the schema stable, but it only ever sees one fixed
     * region of the collection — so a field introduced recently and populated on a handful of active
     * documents is invisible no matter how large the slice gets.
     * <p>
     * The recency slice closes that gap by taking the most recently updated documents. It is only used
     * when recencyField is set and the collection carries an index whose first key is that field:
     * sorting a large collection on an unindexed field would push DocumentDB into an in-memory sort.
     * Requiring the index also keeps the behaviour opt-in per collection without extra configuration.
     *
     * @param numObjToSample Documents to read in the unsorted base slice.
     * @param numRecentObjToSample Documents to read in the recency slice; 0 disables it.
     * @param recencyField Field to sort the recency slice on, descending; null disables it.
     */
    public static Schema inferSchema(MongoDatabase db, TableName table, int numObjToSample,
            int numRecentObjToSample, String recencyField)
    {
        SchemaBuilder schemaBuilder = SchemaBuilder.newBuilder();
        int[] counts = new int[] {0, 0};

        boolean useRecency = numRecentObjToSample > 0 && recencyField != null && !recencyField.isEmpty()
                && hasIndexOn(db, table.getTableName(), recencyField);
        if (numRecentObjToSample > 0 && !useRecency) {
            logger.info("inferSchema: recency slice skipped for table[{}] — recencyField[{}] is unset or not indexed.",
                    table.getTableName(), recencyField);
        }

        try {
            if (useRecency) {
                try (MongoCursor<Document> docs = db.getCollection(table.getTableName()).find()
                        .sort(new Document(recencyField, -1))
                        .batchSize(numRecentObjToSample).limit(numRecentObjToSample).iterator()) {
                    mergeDocuments(docs, schemaBuilder, counts);
                }
            }

            try (MongoCursor<Document> docs = db.getCollection(table.getTableName()).find()
                    .batchSize(numObjToSample).limit(numObjToSample).iterator()) {
                mergeDocuments(docs, schemaBuilder, counts);
            }

            if (counts[0] == 0) {
                return SchemaBuilder.newBuilder().build();
            }

            Schema schema = schemaBuilder.build();
            if (schema.getFields().isEmpty()) {
                throw new RuntimeException("No columns found after scanning " + counts[1] + " values across " +
                        counts[0] + " documents. Please ensure the collection is not empty and contains at least 1 supported column type.");
            }
            // BAH fork: belt-and-suspenders pass — any STRUCT that ended up with no children
            // (e.g. produced via mergeStructField from two empty parents) is rewritten to VARCHAR
            // so Athena engine v3 never sees "Unknown type: row".
            return sanitizeEmptyStructs(schema);
        }
        finally {
            logger.info("inferSchema: Evaluated {} field values across {} documents (recency slice {}).",
                    counts[1], counts[0], useRecency ? "on: " + recencyField : "off");
        }
    }

    /**
     * BAH fork: folds every document the cursor yields into schemaBuilder, tracking documents in
     * counts[0] and field values in counts[1]. Extracted so the base and recency slices share one
     * union implementation; re-visiting a document present in both slices is harmless, since the merge
     * is idempotent for identical field types.
     */
    private static void mergeDocuments(MongoCursor<Document> docs, SchemaBuilder schemaBuilder, int[] counts)
    {
        while (docs.hasNext()) {
            counts[0]++;
            Document doc = docs.next();
            for (String key : doc.keySet()) {
                counts[1]++;
                Field newField = getArrowField(key, doc.get(key));
                Types.MinorType newType = Types.getMinorTypeForArrowType(newField.getType());
                Field curField = schemaBuilder.getField(key);
                Types.MinorType curType = (curField != null) ? Types.getMinorTypeForArrowType(curField.getType()) : null;

                if (curField == null) {
                    schemaBuilder.addField(newField);
                }
                else if (newType != curType) {
                    //TODO: currently we resolve fields with mixed types by defaulting to VARCHAR. This is _not_ ideal
                    logger.warn("inferSchema: Encountered a mixed-type field[{}] {} vs {}, defaulting to String.",
                            key, curType, newType);
                    schemaBuilder.addStringField(key);
                }
                else if (curType == Types.MinorType.LIST) {
                    schemaBuilder.addField(mergeListField(key, curField, newField));
                }
                else if (curType == Types.MinorType.STRUCT) {
                    schemaBuilder.addField(mergeStructField(key, curField, newField));
                }
            }
        }
    }

    /**
     * BAH fork: true when the collection has an index whose first key is fieldName, so the recency
     * slice can sort on it without DocumentDB falling back to an in-memory sort. Failures are treated
     * as "no index" — losing the recency slice is preferable to failing schema inference outright.
     */
    private static boolean hasIndexOn(MongoDatabase db, String collection, String fieldName)
    {
        try {
            for (Document index : db.getCollection(collection).listIndexes()) {
                Object key = index.get("key");
                if (key instanceof Document) {
                    java.util.Iterator<String> keys = ((Document) key).keySet().iterator();
                    if (keys.hasNext() && fieldName.equals(keys.next())) {
                        return true;
                    }
                }
            }
        }
        catch (RuntimeException ex) {
            logger.warn("hasIndexOn: could not list indexes for collection[{}], assuming no index on[{}].",
                    collection, fieldName, ex);
        }
        return false;
    }

    /**
     * BAH fork: walks the inferred schema and replaces any STRUCT field that has no children
     * (an "empty row" type) with a VARCHAR field of the same name. Athena engine v3 rejects empty
     * struct types with "TYPE_NOT_FOUND: Unknown type: row" at query-planning time, which used to
     * break every SELECT against an affected collection. After this pass, the worst case becomes a
     * VARCHAR column the user can still inspect (typically empty / null).
     */
    private static Schema sanitizeEmptyStructs(Schema schema)
    {
        SchemaBuilder sb = SchemaBuilder.newBuilder();
        for (Field f : schema.getFields()) {
            sb.addField(sanitizeFieldEmptyStructs(f));
        }
        return sb.build();
    }

    private static Field sanitizeFieldEmptyStructs(Field field)
    {
        Types.MinorType type = Types.getMinorTypeForArrowType(field.getType());
        if (type == Types.MinorType.STRUCT) {
            List<Field> children = field.getChildren();
            if (children == null || children.isEmpty()) {
                logger.warn("sanitizeEmptyStructs: empty STRUCT field[{}] rewritten to VARCHAR.", field.getName());
                return new Field(field.getName(), FieldType.nullable(Types.MinorType.VARCHAR.getType()), null);
            }
            List<Field> rebuilt = new ArrayList<>();
            for (Field child : children) {
                rebuilt.add(sanitizeFieldEmptyStructs(child));
            }
            return new Field(field.getName(), field.getFieldType(), rebuilt);
        }
        if (type == Types.MinorType.LIST) {
            List<Field> children = field.getChildren();
            if (children != null && !children.isEmpty()) {
                List<Field> rebuilt = new ArrayList<>();
                for (Field child : children) {
                    rebuilt.add(sanitizeFieldEmptyStructs(child));
                }
                return new Field(field.getName(), field.getFieldType(), rebuilt);
            }
        }
        return field;
    }

    /**
     * Used to merge LIST Field into a single Field. If called with two identical LISTs the output is essentially
     * the same as either of the inputs.
     *
     * @param fieldName The name of the merged Field.
     * @param curParentField The current field to use as the base for the merge.
     * @param newParentField The new field to merge into the base.
     * @return The merged field.
     */
    private static Field mergeListField(String fieldName, Field curParentField, Field newParentField)
    {
        //Apache Arrow lists have a special child that holds the concrete type of the list.
        Field curInner = curParentField.getChildren().get(0);
        Field newInner = newParentField.getChildren().get(0);
        Types.MinorType curInnerType = Types.getMinorTypeForArrowType(curInner.getType());
        Types.MinorType newInnerType = Types.getMinorTypeForArrowType(newInner.getType());

        if (curInnerType == Types.MinorType.LIST && newInnerType == Types.MinorType.LIST) {
            return FieldBuilder.newBuilder(fieldName, Types.MinorType.LIST.getType())
                    .addField(mergeStructField("", curInner, newInner)).build();
        }
        // BAH fork: both elements are STRUCT — union their children so the list element keeps
        // every field seen across documents. Upstream kept only the first occurrence, silently
        // dropping fields that appear only in later documents (e.g. an optional school_year_id
        // in tutor.course_offerings).
        else if (curInnerType == Types.MinorType.STRUCT && newInnerType == Types.MinorType.STRUCT) {
            return FieldBuilder.newBuilder(fieldName, Types.MinorType.LIST.getType())
                    .addField(mergeStructField("", curInner, newInner)).build();
        }
        else if (curInnerType != newInnerType) {
            // BAH fork: a list that is EMPTY in some documents infers as LIST<VARCHAR> (the type
            // erasure default in getArrowField) and then collides here with the real element type
            // from populated documents. When the real element is a STRUCT — i.e. an array of
            // sub-documents such as tutor.course_offerings — keep the STRUCT instead of degrading
            // the whole column to a string. Degrading made the object array unreadable: it
            // surfaced as array<varchar> whose elements all came back null.
            if (curInnerType == Types.MinorType.STRUCT) {
                return curParentField;
            }
            if (newInnerType == Types.MinorType.STRUCT) {
                return newParentField;
            }
            //TODO: genuinely mixed scalar element types still default to VARCHAR (unchanged).
            logger.warn("mergeListField: Encountered a mixed-type list field[{}] {} vs {}, defaulting to String.",
                    fieldName, curInnerType, newInnerType);
            return FieldBuilder.newBuilder(fieldName, Types.MinorType.LIST.getType()).addStringField("").build();
        }

        return curParentField;
    }

    /**
     * Used to merge STRUCT Field into a single Field. If called with two identical STRUCTs the output is essentially
     * the same as either of the inputs.
     *
     * @param fieldName The name of the merged Field.
     * @param curParentField The current field to use as the base for the merge.
     * @param newParentField The new field to merge into the base.
     * @return The merged field.
     */
    private static Field mergeStructField(String fieldName, Field curParentField, Field newParentField)
    {
        FieldBuilder union = FieldBuilder.newBuilder(fieldName, Types.MinorType.STRUCT.getType());
        for (Field nextCur : curParentField.getChildren()) {
            union.addField(nextCur);
        }

        for (Field nextNew : newParentField.getChildren()) {
            Field curField = union.getChild(nextNew.getName());
            if (curField == null) {
                union.addField(nextNew);
                continue;
            }

            Types.MinorType newType = Types.getMinorTypeForArrowType(nextNew.getType());
            Types.MinorType curType = Types.getMinorTypeForArrowType(curField.getType());

            if (curType != newType) {
                //TODO: currently we resolve fields with mixed types by defaulting to VARCHAR. This is _not_ ideal
                //for various reasons but also because it will cause predicate odities if used in a filter.
                logger.warn("mergeStructField: Encountered a mixed-type field[{}] {} vs {}, defaulting to String.",
                        nextNew.getName(), newType, curType);

                union.addStringField(nextNew.getName());
            }
            else if (curType == Types.MinorType.LIST) {
                union.addField(mergeListField(nextNew.getName(), curField, nextNew));
            }
            else if (curType == Types.MinorType.STRUCT) {
                union.addField(mergeStructField(nextNew.getName(), curField, nextNew));
            }
        }

        return union.build();
    }

    /**
     * Infers the type of a single DocumentDB document field.
     *
     * @param key The key of the field we are attempting to infer.
     * @param value A value from the key whose type we are attempting to infer.
     * @return The Apache Arrow field definition of the inferred key/value.
     */
    private static Field getArrowField(String key, Object value)
    {
        if (value instanceof String) {
            return new Field(key, FieldType.nullable(Types.MinorType.VARCHAR.getType()), null);
        }
        else if (value instanceof Integer) {
            return new Field(key, FieldType.nullable(Types.MinorType.INT.getType()), null);
        }
        else if (value instanceof Long) {
            return new Field(key, FieldType.nullable(Types.MinorType.BIGINT.getType()), null);
        }
        else if (value instanceof Boolean) {
            return new Field(key, FieldType.nullable(Types.MinorType.BIT.getType()), null);
        }
        else if (value instanceof Float) {
            return new Field(key, FieldType.nullable(Types.MinorType.FLOAT4.getType()), null);
        }
        else if (value instanceof Double) {
            return new Field(key, FieldType.nullable(Types.MinorType.FLOAT8.getType()), null);
        }
        else if (value instanceof Date) {
            return new Field(key, FieldType.nullable(Types.MinorType.DATEMILLI.getType()), null);
        }
        else if (value instanceof BsonTimestamp) {
            return new Field(key, FieldType.nullable(Types.MinorType.DATEMILLI.getType()), null);
        }
        else if (value instanceof ObjectId) {
            return new Field(key, FieldType.nullable(Types.MinorType.VARCHAR.getType()), null);
        }
        else if (value instanceof List) {
            Field child;
            if (((List) value).isEmpty()) {
                logger.warn("getArrowType: Encountered an empty List/Array for field[{}], defaulting to List<String> due to type erasure.", key);
                return FieldBuilder.newBuilder(key, Types.MinorType.LIST.getType()).addStringField("").build();
            }
            else {
                child = getArrowField("", ((List) value).get(0));
            }
            return new Field(key, FieldType.nullable(Types.MinorType.LIST.getType()),
                    Collections.singletonList(child));
        }
        else if (value instanceof Document) {
            Document doc = (Document) value;
            // BAH fork: an empty sub-document (`{}`) is returned as an empty STRUCT (no children),
            // NOT downgraded to VARCHAR here. This matters for fields that are empty in some
            // documents and populated in others (e.g. personal_info.location.coordinates, empty for
            // ~3200 users without an address but {latitude, longitude} for the rest). Downgrading the
            // empty case to VARCHAR would make it collide with the populated STRUCT during the schema
            // union and the whole column would degrade to a string, so `.latitude`/`.longitude` access
            // would break. Leaving it as an empty STRUCT lets mergeStructField() union it with the
            // populated occurrences and keep the real {latitude, longitude} shape.
            // The TYPE_NOT_FOUND safeguard for fields that are empty across the ENTIRE sample is still
            // handled centrally by sanitizeEmptyStructs() after the union completes.
            List<Field> children = new ArrayList<>();
            for (String childKey : doc.keySet()) {
                Object childVal = doc.get(childKey);
                Field child = getArrowField(childKey, childVal);
                children.add(child);
            }
            return new Field(key, FieldType.nullable(Types.MinorType.STRUCT.getType()), children);
        }
        else if (value instanceof DBRef) {
            List<Field> children = new ArrayList<>();
            children.add(new Field("_db", FieldType.nullable(Types.MinorType.VARCHAR.getType()), null));
            children.add(new Field("_ref", FieldType.nullable(Types.MinorType.VARCHAR.getType()), null));
            children.add(new Field("_id", FieldType.nullable(Types.MinorType.VARCHAR.getType()), null));
            return new Field(key, FieldType.nullable(Types.MinorType.STRUCT.getType()), children);
        }

        String className = (value == null || value.getClass() == null) ? "null" : value.getClass().getName();
        logger.warn("Unknown type[" + className + "] for field[" + key + "], defaulting to varchar.");
        return new Field(key, FieldType.nullable(Types.MinorType.VARCHAR.getType()), null);
    }
}
