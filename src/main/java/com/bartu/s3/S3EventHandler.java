package com.bartu.s3;

import com.amazonaws.SdkClientException;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectInputStream;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.CreateQueueRequest;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import com.amazonaws.services.sqs.model.SendMessageResult;
import org.apache.commons.collections4.MultiValuedMap;
import org.apache.commons.collections4.multimap.ArrayListValuedHashMap;
import org.json.*;


public class S3EventHandler implements RequestHandler<S3Event, String> {
    private static final AmazonS3 s3Client = AmazonS3Client.builder()
            .withCredentials(new DefaultAWSCredentialsProviderChain())
            .build();
    @Override
    public String handleRequest(S3Event input, Context context) {
        final LambdaLogger logger = context.getLogger();
        JSONObject errorOutput = new JSONObject();

        // Check the records
        if(input.getRecords().isEmpty()){
            System.out.println("No records found");
            return errorOutput.toString();
        }
        String currentCustomer = "", currentOrder = "";

        // Process the records
        for(S3EventNotification.S3EventNotificationRecord record: input.getRecords()){
            String bucketName = record.getS3().getBucket().getName();
            String objectKey = record.getS3().getObject().getKey();



            String[] objectKeyArray = objectKey.split("_");
            String date = objectKeyArray[1];

            String[] types = {"customers", "orders", "items"};

            // Get all objects
            List<S3Object> s3Objects = new ArrayList<>();
            try {
                for(String type: types) {
                    S3Object s3Object = s3Client.getObject(bucketName, type + "_" + date);
                    s3Objects.add(s3Object);
                }
            } catch (SdkClientException e) {
                System.out.println("All files are not present in S3 bucket");
                return "";
            }

            List<String> customers = new ArrayList<>();
            MultiValuedMap<String, String> orders = new ArrayListValuedHashMap<>();
            MultiValuedMap<String, BigDecimal> items = new ArrayListValuedHashMap<>();

            for(S3Object s3Object : s3Objects)
            {
                S3ObjectInputStream inputStream = s3Object.getObjectContent();
                String line;
                try(final BufferedReader br = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))){
                    // Skip the first line
                    br.readLine();

                    while((line = br.readLine()) != null){
                        try {
                            // Convert the line to value list
                            String[] values = line.split(";");

                            // If this is a customer file, add customer_reference value to customers list
                            if (s3Object.getKey().contains("customer")) {
                                currentCustomer = values[3];
                                customers.add(currentCustomer);
                            }
                            // If this is an orders file, add order_reference value to orders list
                            else if (s3Object.getKey().contains("orders")) {
                                currentOrder = values[3];
                                orders.put(values[1], currentOrder);
                            }
                            // If this is an items file, add total price value to orders list
                            else {
                                // Replacing , with . for Turkish csv files
                                values[4] = values[4].replaceAll(",",".");
                                items.put(values[1], new BigDecimal(values[4]));
                            }
                        } catch (Exception e)
                        {
                            System.out.println("Error occurred in the loop:" + e.getMessage());
                            errorOutput.put("type","error_message");
                            errorOutput.put("customer_reference", currentCustomer);
                            errorOutput.put("order_reference", currentOrder);
                            errorOutput.put("message", e.toString());

                            sendMessage(errorOutput);
                        }
                    }
                } catch (IOException e){
                    logger.log("Error while reading object: " + e);
                }
            }

            for(String customer: customers)
            {
                // Prepare customer message -> loop through customer list and calculate total spent
                currentCustomer = customer;
                JSONObject customerOutput = new JSONObject();
                customerOutput.put("type", "customer_message");
                customerOutput.put("customer_reference", customer);
                Collection<String> orderList = orders.get(customer);
                customerOutput.put("number_of_orders", orderList.size());
                BigDecimal totalSpent = BigDecimal.valueOf(0);
                for (String order : orderList) {
                    currentOrder = order;
                    Collection<BigDecimal> itemList = items.get(order);
                    for(BigDecimal item : itemList)
                    {
                        totalSpent = totalSpent.add(item);
                    }
                }
                customerOutput.put("total_amount_spent", totalSpent);
                sendMessage(customerOutput);
            }


        }
        return "";
    }

    private String sendMessage(JSONObject msg) {
        AmazonSQS sqs = AmazonSQSClientBuilder.standard()
                .withRegion(Regions.EU_CENTRAL_1)
                .build();

        CreateQueueRequest createQueueRequest = new CreateQueueRequest("customer_queue");
        String myQueueURL = sqs.createQueue(createQueueRequest).getQueueUrl();

        System.out.println("Sending msg '" + msg + "' to Q: " + myQueueURL);

        SendMessageResult smr = sqs.sendMessage(new SendMessageRequest()
                .withQueueUrl(myQueueURL)
                .withMessageBody(msg.toString()));

        return "SendMessage succeeded with messageId " + smr.getMessageId()
                + ", sequence number " + smr.getSequenceNumber() + "\n";
    }
}
