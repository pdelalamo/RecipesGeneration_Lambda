package com.fitmymacros;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import software.amazon.awssdk.services.ssm.model.SsmException;

public class OpenAILambda implements RequestHandler<Object, Object> {

    private static final String OPENAI_API_ENDPOINT = "https://api.openai.com/v1/completions";
    private static final String OPENAI_API_KEY_NAME = "OpenAI-API_Key_Encrypted";

    @Override
    public Object handleRequest(Object input, Context context) {

        String openAiKey = this.getOpenAIKey();
        String requestBody = this.generateRequestBody();

        HttpClient httpClient = HttpClient.newHttpClient();
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

    /**
     * This method gets the openai key from env variable (if exists) or from the
     * parameter store
     * 
     * @return
     */
    private String getOpenAIKey() {
        String openAiKey;

        // Check if the environment variable is set
        String envOpenAiKey = System.getenv("OPENAI_API_KEY");
        if (envOpenAiKey != null && !envOpenAiKey.isEmpty()) {
            openAiKey = envOpenAiKey;
        } else {
            openAiKey = this.getOpenAIKeyFromParameterStore();
        }
        return openAiKey;
    }

    /**
     * This method retrieves the clear text value for the openai key from the
     * parameter store
     * 
     * @return
     */
    private String getOpenAIKeyFromParameterStore() {
        SsmClient ssmClient = SsmClient.builder()
                .region(Region.EU_WEST_3)
                .build();

        try {
            GetParameterRequest parameterRequest = GetParameterRequest.builder()
                    .name(OPENAI_API_KEY_NAME)
                    .build();

            GetParameterResponse parameterResponse = ssmClient.getParameter(parameterRequest);
            return parameterResponse.parameter().value();

        } catch (SsmException e) {
            System.err.println("Error getting parameter: " + e.getMessage());
            System.exit(1);
        } finally {
            ssmClient.close();
        }
        return null;
    }

    /**
     * This method generates the request body that will be sent to the openai api
     * 
     * @return
     */
    private String generateRequestBody() {
        String prompt = "please generate a recipe, that contains 700kcal with at least 50g of protein, using chicken. Give me the macros and cooking process.";
        int maxTokens = 500;
        String modelName = "gpt-3.5-turbo-instruct";
        return String.format("{\"prompt\": \"%s\", \"max_tokens\": %d, \"model\": \"%s\"}", prompt,
                maxTokens, modelName);
    }

}
