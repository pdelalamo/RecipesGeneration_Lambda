package com.fitmymacros;

import java.util.List;
import java.util.Arrays;
import java.util.Map;
import java.util.HashMap;
import java.util.stream.Collectors;

import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitmymacros.model.ChatCompletionResponse;
import com.fitmymacros.model.ChatCompletionResponseChoice;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import software.amazon.awssdk.services.ssm.model.SsmException;

public class OpenAILambda implements RequestHandler<Map<String, Object>, Object> {

    private static final String OPENAI_API_KEY_NAME = "OpenAI-API_Key_Encrypted";
    private static final String OPENAI_MODEL_NAME = "OpenAI-Model";
    private static final String OPENAI_MODEL_TEMPERATURE = "OpenAI-Model-Temperature";
    private static final String OPENAI_MAX_TOKENS = "OpenAI-Max-Tokens";
    private static final String URL = "https://api.openai.com/v1/chat/completions";
    
    private final SsmClient ssmClient;
    private final DynamoDbClient dynamoDbClient;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;
    
    private final String openAIApiKey;
    private final String openAIModel;
    private final Double modelTemperature;
    private final Integer modelMaxTokens;
    
    private static final List<String> FRUIT_UNITS = List.of(
            "Apple", "Banana", "Orange", "Peach", "Kiwi", "Pear", 
            "Cherry", "Plum", "Apricot", "Papaya", "Avocado", 
            "Grapefruit", "Lemon", "Lime", "Tangerine", "Cantaloupe", 
            "Honeydew melon", "Nectarine", "Persimmon", "Dragon fruit", 
            "Jackfruit", "Star fruit", "Ackee", "Plantain", "Coconut", 
            "Mangosteen", "Feijoa", "Kumquat", "Pummelo", "Satsuma", "Ugli fruit");

    public OpenAILambda() {
        Region region = Region.EU_WEST_3;
        this.ssmClient = SsmClient.builder().region(region).build();
        this.dynamoDbClient = DynamoDbClient.builder().region(region).build();
        this.openAIApiKey = getParameterValue(OPENAI_API_KEY_NAME);
        this.openAIModel = getParameterValue(OPENAI_MODEL_NAME);
        this.modelTemperature = Double.valueOf(getParameterValue(OPENAI_MODEL_TEMPERATURE));
        this.modelMaxTokens = Integer.valueOf(getParameterValue(OPENAI_MAX_TOKENS));
        this.objectMapper = new ObjectMapper();
        this.webClient = WebClient.create();
    }

    @Override
    public Object handleRequest(Map<String, Object> input, Context context) {
        try {
            Map<String, String> queryParams = extractQueryString(input);
            String prompt = generatePrompt(queryParams);
            Mono<ChatCompletionResponse> completionResponseMono = sendRequestToOpenAI(prompt);
            return handleAIResponse(completionResponseMono);
        } catch (Exception e) {
            return buildErrorResponse(e.getMessage());
        }
    }

    /**
     * Extracts query string parameters from the input map.
     * 
     * @param input The input map containing request data.
     * @return A map of query string parameters.
     */
    private Map<String, String> extractQueryString(Map<String, Object> input) {
        Map<String, Object> queryStringMap = (Map<String, Object>) input.get("queryStringParameters");
        if (queryStringMap != null) {
            String queryString = (String) queryStringMap.get("querystring");
            if (queryString != null) {
                return parseQueryString(queryString);
            }
        }
        return Map.of();
    }

    /**
     * Parses a query string into a map.
     * 
     * @param queryString The query string to parse.
     * @return A map of query parameters.
     */
    private Map<String, String> parseQueryString(String queryString) {
        return Arrays.stream(queryString.replaceAll("[{}]", "").split(", "))
            .map(pair -> pair.split("="))
            .collect(Collectors.toMap(
                keyValue -> keyValue[0],
                keyValue -> keyValue.length > 1 ? keyValue[1] : "true"
            ));
    }

    /**
     * Retrieves a parameter value from AWS SSM.
     * 
     * @param parameterName The name of the parameter to retrieve.
     * @return The decrypted parameter value.
     */
    private String getParameterValue(String parameterName) {
        try {
            GetParameterRequest parameterRequest = GetParameterRequest.builder()
                    .name(parameterName)
                    .withDecryption(true)
                    .build();
            GetParameterResponse parameterResponse = this.ssmClient.getParameter(parameterRequest);
            return parameterResponse.parameter().value();
        } catch (SsmException e) {
            System.err.println("SSM Error: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    /**
     * Generates a prompt based on query parameters.
     * 
     * @param input A map of query parameters.
     * @return The generated prompt as a string.
     */
    private String generatePrompt(Map<String, String> input) {
        String userId = input.get("userId");
        int calories = Integer.parseInt(input.get("calories"));
        int protein = Integer.parseInt(input.get("protein"));
        int carbs = Integer.parseInt(input.get("carbs"));
        int fat = Integer.parseInt(input.get("fat"));

        QueryResponse queryResponse = getUserData(userId);
        Map<String, AttributeValue> userData = queryResponse.items().get(0);

        String nutrientGoals = String.format(
            "Provide 5 recipes based on %d calories, %d grams of protein, %d grams of carbs, and %d grams of fat. ",
            calories, protein, carbs, fat);

        String satietySettings = generateSatietySettings(input.get("satietyLevel"));
        String ingredientDetails = generateIngredientDetails(userData, input.get("measureUnit"),
            Boolean.parseBoolean(input.get("anyIngredientsMode")));
        String dietaryPreferences = generateDietaryPreferences(input, userData);

        return nutrientGoals + satietySettings + ingredientDetails + dietaryPreferences;
    }

    /**
     * Generates satiety settings for the prompt.
     */
    private String generateSatietySettings(String satietyLevel) {
        if (isNonEmpty(satietyLevel)) {
            return String.format("Ensure they are %s. ", satietyLevel);
        }
        return "";
    }
    
    /**
     * Generates ingredient details for the prompt.
     */
    private String generateIngredientDetails(Map<String, AttributeValue> userData,
                                             String measureUnit, boolean anyIngredientsMode) {
        if (!anyIngredientsMode) {
            return " Include only these ingredients available at home: " +
                   userData.get("food").m().entrySet().stream()
                           .map(entry -> formatFoodItem(entry, measureUnit))
                           .collect(Collectors.joining(", ")) + ". ";
        }
        return "";
    }

    /**
     * Formats a food item entry for inclusion in the prompt.
     */
    private String formatFoodItem(Map.Entry<String, AttributeValue> entry, String measureUnit) {
        String foodName = entry.getKey();
        AttributeValue quantityAttr = entry.getValue();
        int quantity = parseQuantity(quantityAttr);
        return quantity > 0 ? String.format("%d %s of %s", quantity, measureUnit, foodName) : "";
    }

    /**
     * Parses the quantity of an AttributeValue.
     */
    private int parseQuantity(AttributeValue quantityAttr) {
        if (quantityAttr.n() != null) {
            return Integer.parseInt(quantityAttr.n());
        } else if (quantityAttr.s() != null && !quantityAttr.s().equalsIgnoreCase("0")) {
            return Integer.parseInt(quantityAttr.s());
        }
        return 0;
    }

    /**
     * Generates dietary preferences for the prompt.
     */
    private String generateDietaryPreferences(Map<String, String> input, Map<String, AttributeValue> userData) {
        StringBuilder preferences = new StringBuilder();
        appendDietLimitations(preferences, userData);
        appendUserAttributes(preferences, input);
        return preferences.toString();
    }

    private void appendDietLimitations(StringBuilder preferences, Map<String, AttributeValue> userData) {
        boolean userIsVegan = Boolean.parseBoolean(userData.getOrDefault("vegan", AttributeValue.builder().bool(false)).bool().toString());
        boolean userIsVegetarian = Boolean.parseBoolean(userData.getOrDefault("vegetarian", AttributeValue.builder().bool(false)).bool().toString());
        
        if (userIsVegan) {
            preferences.append("Ensure recipes are vegan-friendly. ");
        } else if (userIsVegetarian) {
            preferences.append("Ensure recipes are vegetarian-friendly. ");
        }
    }

    private void appendUserAttributes(StringBuilder preferences, Map<String, String> input) {
        appendIfNonEmpty(preferences, "Focus on %s cuisine. ", input.get("cuisineStyle"));
        appendIfNonEmpty(preferences, "Max cooking time is %s. ", input.get("cookingTime"));
        appendIfNonEmpty(preferences, "Flavor profile: %s. ", input.get("flavor"));
        appendIfNonEmpty(preferences, "Suitable for %s. ", input.get("occasion"));
    }

    private void appendIfNonEmpty(StringBuilder builder, String template, String value) {
        if (isNonEmpty(value)) {
            builder.append(String.format(template, value));
        }
    }

    private boolean isNonEmpty(String value) {
        return value != null && !value.isEmpty();
    }

    /**
     * Retrieves user data from DynamoDB.
     * 
     * @param userId The user ID to query.
     * @return The response from DynamoDB query.
     */
    private QueryResponse getUserData(String userId) {
        try {
            Map<String, AttributeValue> expressionAttributeValues = Map.of(
                    ":uid", AttributeValue.builder().s(userId).build());
            QueryRequest queryRequest = QueryRequest.builder()
                    .tableName("FitMyMacros")
                    .keyConditionExpression("userId = :uid")
                    .expressionAttributeValues(expressionAttributeValues)
                    .build();
            return dynamoDbClient.query(queryRequest);
        } catch (DynamoDbException e) {
            throw new RuntimeException("Error retrieving data from DynamoDB: " + e.getMessage());
        }
    }

    /**
     * Sends the prompt to OpenAI API and returns the response.
     * 
     * @param prompt The prompt string for the AI.
     * @return The Mono of ChatCompletionResponse from OpenAI.
     */
    private Mono<ChatCompletionResponse> sendRequestToOpenAI(String prompt) {
        return webClient.post()
                .uri(URL)
                .headers(headers -> {
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    headers.setBearerAuth(openAIApiKey);
                })
                .bodyValue(objectMapper.writeValueAsString(createRequestBody(prompt)))
                .exchangeToMono(response -> handleApiResponse(response));
    }

    private Mono<ChatCompletionResponse> handleApiResponse(WebClient.ResponseSpec response) {
        return response.bodyToMono(ChatCompletionResponse.class)
                .doOnError(Throwable::printStackTrace);
    }

    private Map<String, Object> createRequestBody(String prompt) {
        return Map.of(
            "model", openAIModel,
            "messages", List.of(
                Map.of("role", "system", "content", generateSystemInstructions()),
                Map.of("role", "user", "content", prompt)
            ),
            "max_tokens", modelMaxTokens,
            "temperature", modelTemperature
        );
    }

    private String generateSystemInstructions() {
        return "You're a helpful assistant, that just returns recipes names and their short description as a JSON with this format: {\"recipe1\": description of the recipe, \"recipe2\": description of the recipe...}";
    }

    /**
     * Handles the AI response and builds a success response.
     * 
     * @param completionResponseMono The Mono for ChatCompletionResponse.
     * @return The response map for a success scenario.
     */
    private Map<String, Object> handleAIResponse(Mono<ChatCompletionResponse> completionResponseMono) {
        ChatCompletionResponse completionResponse = completionResponseMono.block();
        ChatCompletionResponseChoice choice = completionResponse.getChoices().get(0);
        return buildSuccessResponse(choice.getMessage().getContent());
    }

    private Map<String, Object> buildSuccessResponse(String response) {
        return Map.of(
            "statusCode", HttpStatus.OK.value(),
            "body", response
        );
    }

    private String buildErrorResponse(String errorMessage) {
        return "Error occurred: " + errorMessage;
    }
}
