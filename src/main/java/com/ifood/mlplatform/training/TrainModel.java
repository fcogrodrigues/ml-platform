package com.ifood.mlplatform.training;

import smile.data.DataFrame;
import smile.data.formula.Formula;
import smile.data.vector.IntVector;
import smile.classification.RandomForest;
import smile.io.Read;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Arrays;

import org.apache.commons.csv.CSVFormat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ifood.mlplatform.model.metadata.ModelMetadata;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TrainModel {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            log.info("Usage: java -jar mini-ml-platform.jar <path-to-csv> <path-to-schema.json> <model-id>");
            System.exit(1);
        }

        String csvPath =       args[0];
        String schemaPathStr = args[1];
        String modelId =       args[2]; 
        Path csvFile = Path.of(csvPath);
        Path schemaPath = Path.of(schemaPathStr);

        if (!Files.exists(csvFile)) {
            throw new IllegalStateException("❌ CSV file not found: " + csvFile);
        }
        if (!Files.exists(schemaPath)) {
            throw new IllegalStateException("❌ Schema file not found: " + schemaPath);
        }

        log.info("📥 Reading CSV from: {}", csvFile);
        CSVFormat csvFormat = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(false)
                .build();

        DataFrame data = Read.csv(csvPath, csvFormat);

        log.info("🧾 Columns found: {}", Arrays.toString(data.names()));

        ModelMetadata metadata = new ObjectMapper().readValue(Files.newInputStream(schemaPath), ModelMetadata.class);
        String labelColumn = metadata.label.name;

        String[] classes = metadata.label.classes.toArray(new String[0]);

        String[] labelValues = data.stringVector(labelColumn).toArray();
        Map<String,Integer> classToIndex = new LinkedHashMap<>();
        for (int i = 0; i < classes.length; i++) {
            classToIndex.put(classes[i], i);
        }

        log.info("✅ Label mapping will follow metadata order: {}", classToIndex);

        int[] labelIndexes = Arrays.stream(labelValues)
                .mapToInt(name -> {
                    Integer idx = classToIndex.get(name);
                    if (idx == null) {
                        throw new IllegalStateException("Unknown label '"+ name +"' not in metadata.classes");
                    }
                    return idx;
                })
                .toArray();

        // replace label column (drop + merge IntVector)
        data = data.drop(labelColumn).merge(IntVector.of(labelColumn, labelIndexes));
        log.info("✅ Label mapping applied: {}", classToIndex);

        Formula formula = Formula.lhs(labelColumn);
        log.info("🧠 Training RandomForest model...");
        RandomForest model = RandomForest.fit(formula, data);
        log.info("✅ Training complete.");

        // Serializar modelo
        ByteArrayOutputStream modelBytes = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(modelBytes)) {
            oos.writeObject(model);
        }

        String bucketName = System.getenv().getOrDefault("BUCKET_NAME", "model");
        String endpoint = System.getenv().getOrDefault("MINIO_ENDPOINT", "http://localhost:9000");
        String accessKey = System.getenv().getOrDefault("MINIO_ACCESS_KEY", "admin");
        String secretKey = System.getenv().getOrDefault("MINIO_SECRET_KEY", "admin123");

        // Upload model and schema to MinIO
        MinioClient minioClient = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();

        log.info("☁️ Uploading artifacts to bucket: {}/{}", bucketName, modelId);                

        // Upload model
        minioClient.putObject(
            PutObjectArgs.builder()
                .bucket(bucketName)
                .object(modelId + "/model.bin")
                .stream(new ByteArrayInputStream(modelBytes.toByteArray()), modelBytes.size(), -1)
                .contentType("application/octet-stream")
                .build()
        );

        // Upload schema
        minioClient.putObject(
            PutObjectArgs.builder()
                .bucket(bucketName)
                .object(modelId + "/schema.json")
                .stream(Files.newInputStream(schemaPath), Files.size(schemaPath), -1)
                .contentType("application/json")
                .build()
        );
       
        log.info("🎉 Training and upload process completed successfully.");
        System.exit(0);
    }

}
