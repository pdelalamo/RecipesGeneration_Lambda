package com.fitmymacros;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.simplesystemsmanagement.AWSSimpleSystemsManagement;
import com.amazonaws.services.simplesystemsmanagement.AWSSimpleSystemsManagementClientBuilder;
import com.amazonaws.services.simplesystemsmanagement.model.GetParameterRequest;
import com.amazonaws.services.simplesystemsmanagement.model.GetParameterResult;

public class OpenAILambda implements RequestHandler<Object, Object> {

    private static final String OPENAI_API_ENDPOINT = "https://api.openai.com/v1/completions";

    @Override
    public Object handleRequest(Object input, Context context) {

        String openAiKey;

        // Check if the environment variable is set
        String envOpenAiKey = System.getenv("OPENAI_API_KEY");
        if (envOpenAiKey != null && !envOpenAiKey.isEmpty()) {
            openAiKey = envOpenAiKey;
        } else {
            AWSSimpleSystemsManagement ssmClient = AWSSimpleSystemsManagementClientBuilder.defaultClient();
            String parameterName = "OpenAI-API_Key_Encrypted";

            GetParameterRequest parameterRequest = new GetParameterRequest()
                    .withName(parameterName)
                    .withWithDecryption(true);
            GetParameterResult parameterResult = ssmClient.getParameter(parameterRequest);
            openAiKey = parameterResult.getParameter().getValue();
        }

        String prompt = "please generate a recipe, that contains 700kcal with at least 50g of protein, using chicken. Give me the macros and cooking process.";
        int maxTokens = 500;
        String modelName = "gpt-3.5-turbo-instruct";
        String requestBody = String.format("{\"prompt\": \"%s\", \"max_tokens\": %d, \"model\": \"%s\"}", prompt,
                maxTokens, modelName);

        // Create an HTTP client
        HttpClient httpClient = HttpClient.newHttpClient();

        // Create an HTTP request
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(OPENAI_API_ENDPOINT))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + openAiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> responseBody = new HashMap<>();
            responseBody.put("statusCode", 200);
            responseBody.put("body", response.body().toString());
            return responseBody;
        } catch (Exception e) {
            e.printStackTrace();
            return "Error occurred: " + e.getMessage();
        }
    }
}
