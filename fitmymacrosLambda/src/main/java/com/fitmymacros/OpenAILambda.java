package fitmymacrosLambda.src.main.java.com.fitmymacros;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;

public class OpenAILambda implements RequestHandler<Object, Object> {

    private static final String OPENAI_API_KEY = "YOUR_OPENAI_API_KEY";
    private static final String OPENAI_API_ENDPOINT = "https://api.openai.com/v1/completions";

    @Override
    public Object handleRequest(Object input, Context context) {
        // Construct the request body
        String requestBody = "{\"prompt\": \"Once upon a time\", \"max_tokens\": 50}";

        // Create an HTTP client
        HttpClient httpClient = HttpClient.newHttpClient();

        // Create an HTTP request
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(OPENAI_API_ENDPOINT))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + OPENAI_API_KEY)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        try {
            // Send the HTTP request and retrieve the response
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            // Print the response body
            System.out.println("Response: " + response.body());

            // You can handle the response here as needed

            // For example, if you want to return the response body as the output of the Lambda function
            return response.body();
        } catch (Exception e) {
            // Handle exceptions
            e.printStackTrace();
            return "Error occurred: " + e.getMessage();
        }
    }
}

