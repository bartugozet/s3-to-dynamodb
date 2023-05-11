package com.bartu.s3;

import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.model.*;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.lambda.runtime.events.SQSEvent.SQSMessage;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class SQSHandler implements RequestHandler<SQSEvent, String>{
    @Override
    public String handleRequest(SQSEvent event, Context context)
    {
        AmazonDynamoDB dynamoDB = AmazonDynamoDBClientBuilder
                .standard()
                .withRegion(Regions.EU_CENTRAL_1)
                .withCredentials(new DefaultAWSCredentialsProviderChain())
                .build();


        for(SQSMessage msg : event.getRecords()){
            System.out.println("Read from  SQS: " + new String(msg.getBody()));
            PutItemRequest request = new PutItemRequest();
            request.setTableName("MESSAGE");
            request.setReturnConsumedCapacity(ReturnConsumedCapacity.TOTAL);

            request.setReturnValues(ReturnValue.ALL_OLD);

            Map<String, AttributeValue> map = new HashMap<>();
            map.put("Id", new AttributeValue(UUID.randomUUID().toString()));

            map.put("Message", new AttributeValue(msg.getBody()));

            request.setItem(map);
            try {
                /* Send Put Item Request */

                PutItemResult result = dynamoDB.putItem(request);

                System.out.println("Status : " + result.getSdkHttpMetadata().getHttpStatusCode());

                System.out.println("Consumed Capacity : " + result.getConsumedCapacity().getCapacityUnits());

                /* Printing Old Attributes Name and Values */
                if(result.getAttributes() != null) {
                    result.getAttributes().entrySet().stream()
                            .forEach( e -> System.out.println(e.getKey() + " " + e.getValue()));
                }

            } catch (Exception e) {

                System.out.println(e.getMessage());

            }
        }



        return "";
    }
}