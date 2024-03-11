package com.fitmymacros;

import java.util.Map;
import java.util.UUID;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.LambdaAsyncClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;

public class OpenAILambda implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final String TARGET_LAMBDA = "openaicalllambda-stack-OpenAILambdaFunction-arDhAYU1DI4B";
    private final LambdaAsyncClient lambdaAsyncClient;

    public OpenAILambda() {
        this.lambdaAsyncClient = LambdaAsyncClient.create();
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        try {
            String opId = generateUniqueIdentifier();
            this.invokeResultSavingLambda(opId, input.getQueryStringParameters());
            return buildSuccessResponse(opId);

        } catch (Exception e) {
            return this.buildErrorResponse(e.getMessage());
        }
    }

    /**
     * This method asynchronously calls a lambda function, that will handle all the
     * logic
     * 
     * @param opId
     */
    private void invokeResultSavingLambda(String opId, Map<String, String> queryParams) {
        InvokeRequest request = InvokeRequest.builder()
                .functionName(this.TARGET_LAMBDA)
                .payload(SdkBytes
                        .fromUtf8String("{ \"opId\": \"" + opId + "\", \"queryParams\": \"" + queryParams + "\" }"))
                .build();

        this.lambdaAsyncClient.invoke(request)
                .whenComplete((response, ex) -> {
                    if (ex != null) {
                        System.err.println("Error invoking Lambda function: " + ex.getMessage());
                    } else {
                        System.out.println("Lambda function invoked successfully");
                    }
                });
    }

    /**
     * This method generates an unique idenfifier of the operation, that will be
     * used as a primary key for storing the result of the openAI call. It's
     * returned to the user, so that he can use for retrieving the response from
     * openAI later on
     * 
     * @return
     */
    private String generateUniqueIdentifier() {
        return UUID.randomUUID().toString();
    }

    private APIGatewayProxyResponseEvent buildSuccessResponse(String message) {
        APIGatewayProxyResponseEvent responseEvent = new APIGatewayProxyResponseEvent();
        responseEvent.setBody(message);
        responseEvent.setStatusCode(200);
        return responseEvent;
    }

    private APIGatewayProxyResponseEvent buildErrorResponse(String errorMessage) {
        APIGatewayProxyResponseEvent responseEvent = new APIGatewayProxyResponseEvent();
        responseEvent.setBody(errorMessage);
        responseEvent.setStatusCode(500);
        return responseEvent;
    }

}
