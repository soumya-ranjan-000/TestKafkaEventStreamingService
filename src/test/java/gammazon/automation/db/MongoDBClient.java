package gammazon.automation.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.json.JsonWriterSettings;
import gammazon.automation.core.ConfigManager;
import gammazon.automation.core.ReportManager;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class MongoDBClient {
    private static MongoClient mongoClient;
    private static MongoDatabase database;

    static {
        String uri = ConfigManager.getInstance().get("MONGODB_URI");
        String dbName = ConfigManager.getInstance().get("MONGODB_DATABASE");
        mongoClient = MongoClients.create(uri);
        database = mongoClient.getDatabase(dbName);
        System.out.println("[MongoDB] Client initialized with URI: " + uri + " and DB: " + dbName);
    }

    public static void listCollections() {
        log("🔍 Listing all collections in database:");
        for (String name : database.listCollectionNames()) {
            log(" ➜ <span style='color: #4DB33D; font-weight: bold;'>" + name + "</span>");
        }
    }

    /**
     * Loads test data from a JSON file into the specified collection.
     * Supports both single objects and arrays of objects.
     */
    public static void loadTestData(String collectionName, String filePath) {
        log("📂 Loading test data from: <b>" + filePath + "</b>");
        try {
            String jsonContent = Files.readString(Paths.get(filePath));
            ObjectMapper mapper = new ObjectMapper();
            JsonNode rootNode = mapper.readTree(jsonContent);
            
            if (rootNode.isArray()) {
                for (JsonNode node : rootNode) {
                    Document doc = Document.parse(node.toString());
                    Object id = doc.get("_id");
                    
                    if (id != null) {
                        getCollection(collectionName).replaceOne(
                                Filters.eq("_id", id),
                                doc,
                                new ReplaceOptions().upsert(true)
                        );
                    } else {
                        getCollection(collectionName).insertOne(doc);
                    }
                }
                log("✅ Successfully seeded <b>" + rootNode.size() + "</b> records into <b>" + collectionName + "</b>");
            } else {
                Document doc = Document.parse(rootNode.toString());
                getCollection(collectionName).insertOne(doc);
                log("✅ Successfully seeded 1 record into <b>" + collectionName + "</b>");
            }
        } catch (Exception e) {
            log("❌ Error loading test data: <span style='color:red'>" + e.getMessage() + "</span>");
        }
    }

    private static void log(String message) {
        String consolePrefix = "[🍃 MongoDB] ";
        System.out.println(consolePrefix + message.replaceAll("<[^>]*>", "")); // Strip HTML for console
        
        if (ReportManager.getTest() != null) {
            String reportIcon = "<i class='fa fa-database' style='color:#4DB33D'></i> ";
            ReportManager.getTest().info(reportIcon + "<b>MongoDB:</b> " + message);
        }
    }

    public static void printCollectionData(String collectionName) {
        log("📊 Fetching contents of collection: <b>" + collectionName + "</b>");
        List<Document> documents = getCollection(collectionName).find().into(new ArrayList<>());
        
        if (documents.isEmpty()) {
            log("⚠️ Collection " + collectionName + " is empty.");
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append("<div style='background-color: #f1f3f4; padding: 15px; border-left: 5px solid #4DB33D; border-radius: 8px; margin: 10px 0;'>");
            sb.append("<h5 style='margin-top: 0; color: #13aa52; font-weight: bold;'>Data Snapshot: ").append(collectionName).append("</h5>");
            sb.append("<pre style='font-family: \"Courier New\", Courier, monospace; color: #202124; font-size: 13px; line-height: 1.5; white-space: pre-wrap;'>");
            
            JsonWriterSettings settings = JsonWriterSettings.builder().indent(true).build();
            for (Document doc : documents) {
                String prettyJson = doc.toJson(settings);
                System.out.println(prettyJson);
                sb.append(prettyJson).append("\n\n");
            }
            
            sb.append("</pre></div>");
            if (ReportManager.getTest() != null) {
                ReportManager.getTest().info(sb.toString());
            }
        }
    }

    public static MongoCollection<Document> getCollection(String collectionName) {
        return database.getCollection(collectionName);
    }

    public static void insertOne(String collectionName, Document document) {
        log("📥 Inserting document into <span style='color:#4DB33D'>" + collectionName + "</span>");
        getCollection(collectionName).insertOne(document);
    }

    public static Document findOne(String collectionName, Bson filter) {
        log("🔍 Finding document in <span style='color:#4DB33D'>" + collectionName + "</span>");
        Document doc = getCollection(collectionName).find(filter).first();
        if (doc != null) {
            log("✅ Record found.");
        } else {
            log("❓ No record found matching filter.");
        }
        return doc;
    }

    public static List<Document> findAll(String collectionName, Bson filter) {
        log("🔍 Finding all documents in <span style='color:#4DB33D'>" + collectionName + "</span>");
        return getCollection(collectionName).find(filter).into(new ArrayList<>());
    }

    public static void deleteOne(String collectionName, Bson filter) {
        log("🗑️ Deleting document from <span style='color:#4DB33D'>" + collectionName + "</span>");
        getCollection(collectionName).deleteOne(filter);
    }

    public static void updateOne(String collectionName, Bson filter, Bson update) {
        log("🆙 Updating document in <span style='color:#4DB33D'>" + collectionName + "</span>");
        getCollection(collectionName).updateOne(filter, update);
    }

    public static void dropCollection(String collectionName) {
        log("🧨 Dropping collection: <span style='color:#d93025'>" + collectionName + "</span>");
        getCollection(collectionName).drop();
    }

    public static void close() {
        if (mongoClient != null) {
            mongoClient.close();
        }
    }
}
