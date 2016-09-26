package org.fandanzle.mongi.vertx;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.fandanzle.mongi.IMongi;
import org.fandanzle.mongi.annotation.*;

import org.fandanzle.mongi.entity.Collection;
import org.fandanzle.mongi.entity.CollectionField;
import org.fandanzle.mongi.entity.CollectionIndex;
import org.fandanzle.mongi.entity.Database;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.mongo.MongoClient;
import org.apache.log4j.Logger;
import org.reflections.Reflections;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/**
 *
 * Created by alexb on 11/02/2016.
 *
 */
public class MongiVertx implements IMongi {

    private static Logger logger = Logger.getLogger(MongiVertx.class);

    // Boolean check, If set to true the schema will dropped and rebuild everytime
    // an new instance of Mongi is created
    private Boolean rebuildOnRun = false;

    private Gson jsonParser = new GsonBuilder()
            .setPrettyPrinting()
            .excludeFieldsWithoutExposeAnnotation()
            .serializeNulls()
            .create();

    private String database;

    private MongoClient mongoClient;

    private Database mongiDb = new Database();

    /**
     *
     * @param vertx
     *
     */
    public MongiVertx(Vertx vertx) {
        mongoClient = MongoClient.createShared(vertx, new JsonObject());
    }

    /**
     *
     * @param vertx
     * @param config
     *
     */
    public MongiVertx(Vertx vertx, JsonObject config) {
        mongoClient = MongoClient.createShared(vertx, config);
    }

    /**
     *
     * @return
     */
    public MongoClient getMongoClient(){
        return mongoClient;
    }

    /**
     *
     * @return
     */
    public Database getMongoDatabase(){
        return mongiDb;
    }

    public MongiVertx setRebuild(Boolean rebuild){
        rebuildOnRun = rebuild;
        return this;
    }
    /**
     *
     *
     * This function takes a package name to scan and profiles the schema to insert and the indexs to ensure.
     *
     * @param packageName
     * @return
     */
    public MongiVertx buildOrmSolution(String packageName) {

        System.out.println("Building ORM Solution !");
        // Store our collections
        List<Collection> collectionsList = new ArrayList<>();
        //
        HashMap<String, HashMap<String, String>> collectionIndex = new HashMap<String, HashMap<String, String>>();
        // TODO create IDP providers and store on verticle creation
        // Hashmap to store IDP providers
        // Java reflections, We loads the IDP providers via generics
        Reflections reflections = new Reflections(packageName);
        // Fetch all classes that have the ProviderTypeAnnotation.class annotation
        Set<Class<?>> annotated = reflections.getTypesAnnotatedWith(CollectionDefinition.class);
        // Iterate
        Database database = new Database();

        List<Class> definedClass = new ArrayList<>();
        List<Collection> mappedCollections = new ArrayList<>();

        for (Class ii : annotated) {

            System.out.println(ii.getCanonicalName());

            Annotation collectionDefinition = ii.getAnnotation(CollectionDefinition.class);

            if (collectionDefinition instanceof CollectionDefinition) {

                try {
                    Collection collection = new Collection();
                    collection.setCollectionName(((CollectionDefinition) collectionDefinition).collectionName());
                    collection.setCollectionClass(ii.getCanonicalName());
                    collection.setCollectionClazz(Class.forName(ii.getCanonicalName()));
                    collection.setCollectionFields(getCollectionFields(ii));
                    collection.setCollectionIndexes(getCollectionIndexes(ii));
                    definedClass.add(Class.forName(ii.getCanonicalName()));
                    mappedCollections.add(collection);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            System.out.println("Finished processing entity " + ii.getCanonicalName());
        }

        database.setDatabaseCollections(mappedCollections);
        database.setDatabaseEntities(definedClass);
        mongiDb = database;


        // Iterate the collection annotations set
        createBulkUniqueIndexes(collectionIndex);

        return this;

    }

    /**
     *
     * This function will fetch all defined indexes from the annotated class
     * The returned info will then be applied to mongo
     * @param collectionClass
     *
     */
    private List<CollectionIndex> getCollectionIndexes(Class collectionClass){

        System.out.println("Fetching Collection indexes + " + collectionClass);
        // Get our Annotation and type check
        Annotation ano = collectionClass.getAnnotation(CollectionDefinition.class);
        // Check annotation is instance of ProviderTypeAnnotation.class
        HashMap<String, String> collectIndex = new HashMap<String, String>();

        List<CollectionIndex> indexList = new ArrayList<>();

        System.out.println("Collection Definition " + ano);
        System.out.println("=============================");

        if (ano instanceof CollectionDefinition) {

            Field[] fields = collectionClass.getDeclaredFields();

            for (Field field : fields) {

                UniqueIndex unique = field.getAnnotation(UniqueIndex.class);

                if (unique != null) {

                    CollectionIndex colIndex = new CollectionIndex();
                    colIndex.setIndexField(field.getName());
                    colIndex.setIndexClazz(field.getType().toString());
                    colIndex.setIndexName(unique.indexName());
                    System.out.println( jsonParser.toJson( colIndex ) );
                    indexList.add( colIndex );
                    collectIndex.put(field.getName(), unique.indexName());
                }

            }

        }

        return indexList;
    }

    /**
     *
     * Get all fields defined within an entity
     * @param collectionClass
     *
     */
    private List<CollectionField> getCollectionFields(Class collectionClass){

        // Get our Annotation and type check
        Annotation ano = collectionClass.getAnnotation(CollectionDefinition.class);
        // Check annotation is instance of ProviderTypeAnnotation.class
        HashMap<String, String> collectIndex = new HashMap<String, String>();

        List<CollectionField> fieldList = new ArrayList<>();

        if (ano instanceof CollectionDefinition) {

            CollectionDefinition myAnnotation = (CollectionDefinition) ano;
            Method[] methods = collectionClass.getDeclaredMethods();
            Field[] fields = collectionClass.getDeclaredFields();

            for (Field field : fields) {

                DocumentField documentField = field.getAnnotation(DocumentField.class);
                LinkedCollection linkedCollection = field.getAnnotation(LinkedCollection.class);

                if (documentField != null) {

                    CollectionField collectionField = new CollectionField();
                    collectionField.setFieldClazz(field.getType().toString());
                    collectionField.setFieldName( field.getName());
                    collectionField.setFieldRequired( documentField.required() );

                    if(linkedCollection != null){
                        collectionField.setFieldinternal(false);
                    }

                    fieldList.add(collectionField);

                }

            }

        }
        return fieldList;

    }


    /**
     *
     * @param indexMap
     */
    private void createBulkUniqueIndexes(HashMap<String, HashMap<String, String>> indexMap) {

        System.out.println("=========================================");
        System.out.println("Bulk buiding indexes");
        System.out.println(indexMap);
        System.out.println("=========================================");
        // Iterate the collection annotations set
        for (Map.Entry<String, HashMap<String, String>> entry : indexMap.entrySet()) {
            String key = entry.getKey();
            HashMap<String, String> value = entry.getValue();

            System.out.println("=========================================");
            System.out.println("Bulk Collection index " + key);
            System.out.println("=========================================");

            for (Map.Entry<String, String> index : value.entrySet()) {
                String field = index.getKey();
                String indexName = index.getValue();
        
                // Create our indexes using pass through commands
                mongoClient.runCommand("createIndexes",
                    new JsonObject()
                            .put("createIndexes", key)
                            .put("indexes", new JsonArray()
                                .add(
                                    new JsonObject()
                                        .put("name", indexName)
                                        .put("key", new JsonObject().put(field, 1))
                                        .put("unique", true)
                                        .put("sparse", true)
                                    )
                            ),
                    cr -> {

                        System.out.println(
                                "Creating a new index !!!!"
                        );

                        System.out.print(
                                Json.encodePrettily(
                                        new JsonObject()
                                                .put("createIndexes", key)
                                                .put("indexes", new JsonArray()
                                                                .add(
                                                                        new JsonObject()
                                                                                .put("name", indexName)
                                                                                .put("key", new JsonObject().put(field, 1))
                                                                                .put("unique", true)
                                                                                .put("sparse", true)
                                                                )
                                                )
                                )
                        );

                        if (cr.succeeded()) {
                            JsonObject result = cr.result();
                            logger.info("Collection : " + key);
                            logger.info("DocumentField : " + field);
                            logger.info("IndexName : " + indexName);
                            logger.info("CreateIndexes succeeded result >" + result.encodePrettily());
                        } else {
                            logger.warn("CreateIndexes failed", cr.cause());
                        }
                    });
            }
        }

    }


    /**
     *
     *   Get all collection indexes
     */
    public void listCollectionIndexes() {

        mongoClient.runCommand("getIndexes",
            new JsonObject(),
            cr -> {
                if (cr.succeeded()) {
                    JsonObject result = cr.result();
                    logger.info("CreateIndexes succeeded result >" + result.encodePrettily());
                } else {
                    logger.warn("CreateIndexes failed", cr.cause());
                }
            });

    }

}