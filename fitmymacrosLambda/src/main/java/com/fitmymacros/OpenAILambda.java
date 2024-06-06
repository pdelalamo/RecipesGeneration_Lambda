package com.fitmymacros;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.http.HttpStatusCode;

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

    private static String OPENAI_API_KEY_NAME = "OpenAI-API_Key_Encrypted";
    private static String OPENAI_MODEL_NAME = "OpenAI-Model";
    private static String OPENAI_MODEL_TEMPERATURE = "OpenAI-Model-Temperature";
    private static String OPENAI_MAX_TOKENS = "OpenAI-Max-Tokens";
    private SsmClient ssmClient;
    private String OPENAI_AI_KEY;
    private String OPENAI_MODEL;
    private Double MODEL_TEMPERATURE;
    private Integer MODEL_MAX_TOKENS;
    private DynamoDbClient dynamoDbClient;
    private String URL = "https://api.openai.com/v1/chat/completions";
    private ObjectMapper objectMapper;
    private WebClient webClient;

    public OpenAILambda() {
        this.ssmClient = SsmClient.builder().region(Region.EU_WEST_3).build();
        this.dynamoDbClient = DynamoDbClient.builder().region(Region.EU_WEST_3).build();
        this.OPENAI_AI_KEY = this.getOpenAIKeyFromParameterStore();
        this.OPENAI_MODEL = this.getOpenAIModelFromParameterStore();
        this.MODEL_TEMPERATURE = this.getTemperatureFromParameterStore();
        this.MODEL_MAX_TOKENS = this.getMaxTokensFromParameterStore();
        this.objectMapper = new ObjectMapper();
        this.webClient = WebClient.create();
    }

    @Override
    public Object handleRequest(Map<String, Object> input, Context context) {
        try {
            Map<String, String> queryParams = this.extractQueryString(input);
            System.out.println("input: " + input);
            String prompt = generatePrompt(queryParams);
            System.out.println("prompt: " + prompt);

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", this.OPENAI_MODEL);
            requestBody.put("messages", Arrays.asList(
                    Map.of("role", "system",
                            "content", this.generateSystemInstructions()),
                    Map.of("role", "user",
                            "content", prompt)));
            requestBody.put("max_tokens", this.MODEL_MAX_TOKENS);
            requestBody.put("temperature", MODEL_TEMPERATURE);

            Mono<ChatCompletionResponse> completionResponseMono = webClient.post()
                    .uri(URL)
                    .headers(httpHeaders -> {
                        httpHeaders.setContentType(MediaType.APPLICATION_JSON);
                        httpHeaders.setBearerAuth(OPENAI_AI_KEY);
                    })
                    .bodyValue(objectMapper.writeValueAsString(requestBody))
                    .exchangeToMono(clientResponse -> {
                        HttpStatusCode httpStatus = clientResponse.statusCode();
                        if (httpStatus.is2xxSuccessful()) {
                            return clientResponse.bodyToMono(ChatCompletionResponse.class);
                        } else {
                            Mono<String> stringMono = clientResponse.bodyToMono(String.class);
                            stringMono.subscribe(s -> {
                                System.out.println("Response from Open AI API " + s);
                            });
                            System.out.println("Error occurred while invoking Open AI API");
                            return Mono.error(new Exception(
                                    "Error occurred while generating wordage"));
                        }
                    });
            ChatCompletionResponse completionResponse = completionResponseMono.block();
            List<ChatCompletionResponseChoice> choices = completionResponse.getChoices();
            ChatCompletionResponseChoice aChoice = choices.get(0);
            return buildSuccessResponse(this.parseJsonArray(aChoice.getMessage().getContent()));
        } catch (Exception e) {
            return this.buildErrorResponse(e.getMessage());
        }
    }

    /**
     * This method extracts the query params from the received event
     * 
     * @param input
     * @return
     */
    private Map<String, String> extractQueryString(Map<String, Object> input) {
        Map<String, Object> queryStringMap = (Map<String, Object>) input.get("queryStringParameters");
        if (queryStringMap != null) {
            String queryString = (String) queryStringMap.get("querystring");
            if (queryString != null) {
                return parseQueryString(queryString);
            } else {
                System.out.println("No query string parameters found.");
            }
        } else {
            System.out.println("No queryStringParameters found.");
        }
        return null;
    }

    /**
     * This method converts a String into a Map
     * 
     * @param queryString
     * @return
     */
    private Map<String, String> parseQueryString(String queryString) {
        Map<String, String> queryMap = new HashMap<>();

        // Remove leading and trailing braces if present
        if (queryString.startsWith("{") && queryString.endsWith("}")) {
            queryString = queryString.substring(1, queryString.length() - 1);
        }

        // Split the string by comma and space
        String[] pairs = queryString.split(", ");

        for (String pair : pairs) {
            String[] keyValue = pair.split("=");
            if (keyValue.length == 2) {
                String key = keyValue[0];
                String value = keyValue[1];

                // Handle boolean values
                if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
                    queryMap.put(key, value);
                } else {
                    // For non-boolean values, put the key-value pair in the map
                    queryMap.put(key, value);
                }
            } else if (keyValue.length == 1) {
                // If there's no '=', treat the whole string as a key with a value of "true"
                queryMap.put(keyValue[0], "true");
            }
        }

        return queryMap;
    }

    /**
     * This method retrieves the clear text value for the openai key from the
     * parameter store
     * 
     * @return
     */
    private String getOpenAIKeyFromParameterStore() {
        try {
            GetParameterRequest parameterRequest = GetParameterRequest.builder()
                    .name(OPENAI_API_KEY_NAME)
                    .withDecryption(true)
                    .build();
            GetParameterResponse parameterResponse = this.ssmClient.getParameter(parameterRequest);
            return parameterResponse.parameter().value();

        } catch (SsmException e) {
            System.out.println("SSM Error: " + e.getMessage());
            System.exit(1);
        }
        return null;
    }

    /**
     * This method retrieves the clear text value for the openai model from the
     * parameter store
     * 
     * @return
     */
    private String getOpenAIModelFromParameterStore() {
        try {
            GetParameterRequest parameterRequest = GetParameterRequest.builder()
                    .name(OPENAI_MODEL_NAME)
                    .withDecryption(true)
                    .build();

            GetParameterResponse parameterResponse = this.ssmClient.getParameter(parameterRequest);
            return parameterResponse.parameter().value();

        } catch (SsmException e) {
            System.out.println("SSM Error: " + e.getMessage());
            System.exit(1);
        }
        return null;
    }

    /**
     * This method retrieves the clear value for the openai temperature to use from
     * the
     * parameter store
     * 
     * @return
     */
    private Double getTemperatureFromParameterStore() {
        try {
            GetParameterRequest parameterRequest = GetParameterRequest.builder()
                    .name(OPENAI_MODEL_TEMPERATURE)
                    .withDecryption(true)
                    .build();

            GetParameterResponse parameterResponse = this.ssmClient.getParameter(parameterRequest);
            return Double.valueOf(parameterResponse.parameter().value());

        } catch (SsmException e) {
            System.out.println("SSM Error: " + e.getMessage());
            System.exit(1);
        }
        return null;
    }

    /**
     * This method retrieves the clear value for the openai max tokens to use from
     * the
     * parameter store
     * 
     * @return
     */
    private Integer getMaxTokensFromParameterStore() {
        try {
            GetParameterRequest parameterRequest = GetParameterRequest.builder()
                    .name(OPENAI_MAX_TOKENS)
                    .withDecryption(true)
                    .build();

            GetParameterResponse parameterResponse = this.ssmClient.getParameter(parameterRequest);
            return Integer.valueOf(parameterResponse.parameter().value());

        } catch (SsmException e) {
            System.out.println("SSM Error: " + e.getMessage());
            System.exit(1);
        }
        return null;
    }

    /**
     * This method generates the prompt that will be sent to the openai api
     * 
     * @return
     */
    private String generatePrompt(Map<String, String> input) {
        try {
            String userId = input.get("userId").toString();
            String measureUnit = input.get("measureUnit").toString();
            int calories = Integer.parseInt(input.get("calories").toString());
            int protein = Integer.parseInt(input.get("protein").toString());
            int carbs = Integer.parseInt(input.get("carbs").toString());
            int fat = Integer.parseInt(input.get("fat").toString());
            String satietyLevel = input.get("satietyLevel").toString();
            String precision = input.get("precision").toString(); // exact grams of protein, carbs and fat, or slight
                                                                  // variation?
            boolean anyIngredientsMode = Boolean.parseBoolean(input.get("anyIngredientsMode").toString());
            boolean expandIngredients = Boolean.parseBoolean(input.get("expandIngredients").toString());
            boolean glutenFree = Boolean.parseBoolean(input.get("glutenFree").toString());
            boolean vegan = Boolean.parseBoolean(input.get("vegan").toString());
            boolean vegetarian = Boolean.parseBoolean(input.get("vegetarian").toString());
            String cuisineStyle = input.get("cuisineStyle").toString();
            String cookingTime = input.get("cookingTime").toString();
            String flavor = input.get("flavor").toString();
            String occasion = input.get("occasion").toString();

            QueryResponse queryResponse = this.getUserData(userId);
            Map<String, AttributeValue> userData = queryResponse.items().get(0);
            return this.createPrompt(precision, measureUnit, calories, protein, carbs, fat, satietyLevel,
                    anyIngredientsMode,
                    expandIngredients, glutenFree, vegan, vegetarian, cuisineStyle, cookingTime, flavor, occasion,
                    userData);
        } catch (Exception e) {
            System.out.println("Error while deserializing input params: " + e.getMessage());
            return null;
        }
    }

    /**
     * This method retrieves the data of a user, by its userId
     * 
     * @param userId
     * @return
     */
    private QueryResponse getUserData(String userId) {
        try {
            Map<String, AttributeValue> expressionAttributeValues = new HashMap<>();
            expressionAttributeValues.put(":uid", AttributeValue.builder().s(userId).build());
            String keyConditionExpression = "userId = :uid";

            QueryRequest queryRequest = QueryRequest.builder()
                    .tableName("FitMyMacros")
                    .keyConditionExpression(keyConditionExpression)
                    .expressionAttributeValues(expressionAttributeValues)
                    .build();

            return dynamoDbClient.query(queryRequest);

        } catch (DynamoDbException e) {
            throw new RuntimeException("Error retrieving data from DynamoDB: " + e.getMessage());
        }
    }

    /**
     * This method creates the prompt that will be sent to openAI, based on the data
     * that the user has in the DB (food and quantities, allergies, vegan...) and
     * the actual data for the desired recipe generation (calories, macros...)
     * 
     * @param precision
     * @param measureUnit
     * @param calories
     * @param protein
     * @param carbs
     * @param fat
     * @param satietyLevel
     * @param anyIngredientsMode
     * @param expandIngredients
     * @param glutenFree
     * @param vegan
     * @param vegetarian
     * @param cuisineStyle
     * @param cookingTime
     * @param flavor
     * @param occasion
     * @param userData
     * @return
     */
    private String createPrompt(String precision, String measureUnit, int calories, int protein, int carbs, int fat,
            String satietyLevel, boolean anyIngredientsMode, boolean expandIngredients, boolean glutenFree,
            boolean vegan, boolean vegetarian, String cuisineStyle, String cookingTime, String flavor,
            String occasion, Map<String, AttributeValue> userData) {

        System.out.println("userData: " + userData);
        StringBuilder promptBuilder = new StringBuilder();

        // Target nutritional goals
        promptBuilder.append(
                String.format(
                        "Give me the name of 5 recipes.",
                        precision, calories, protein, measureUnit, carbs, measureUnit, fat, measureUnit));

        // Desired satiety level
        promptBuilder.append(String.format("Ensure they are %s", satietyLevel));

        // Details about available ingredients
        if (!anyIngredientsMode) {
            promptBuilder.append(
                    ". You can only include the following ingredients available at home: ");
            Map<String, AttributeValue> foodMap = userData.get("food").m();
            for (Map.Entry<String, AttributeValue> entry : foodMap.entrySet()) {
                String foodName = entry.getKey();
                AttributeValue quantityAttr = entry.getValue();
                if (quantityAttr.n() != null) { // Check if it's a number
                    int foodQuantity = Integer.parseInt(quantityAttr.n());
                    promptBuilder.append(String.format(", %dg of %s", foodQuantity, foodName));
                } else if (quantityAttr.s() != null) { // Check if it's a string
                    String foodQuantityString = quantityAttr.s();
                    promptBuilder.append(String.format(", %s %s", foodQuantityString, foodName));
                }
            }
        }

        // previous 10 generated recipes
        List<AttributeValue> recipeList = userData.get("previous_recipes").l();
        if (!recipeList.isEmpty()) {
            promptBuilder.append(". If possible, create recipes that heavily differ in ingredients and flavour from:");
            System.out.println("recipeList: " + recipeList);
            recipeList.forEach(recipe -> {
                String recipeName = recipe.s();
                System.out.println("recipe: " + recipeName);
                promptBuilder.append(String.format(" %s,", recipeName));
            });
            // Remove trailing comma
            promptBuilder.deleteCharAt(promptBuilder.length() - 1);
        }

        // Exclude any allergens or intolerances
        List<AttributeValue> allergiesList = userData.get("allergies-intolerances").l();
        System.out.println("allergies: " + allergiesList);
        if (!allergiesList.isEmpty()) {
            promptBuilder.append(", avoiding ingredients such as");
            for (AttributeValue allergy : allergiesList) {
                String allergyName = allergy.s();
                promptBuilder.append(String.format(" %s,", allergyName));
            }
            // Remove trailing comma
            promptBuilder.deleteCharAt(promptBuilder.length() - 1);
        }

        // Vegan diet?
        boolean userIsVegan = userData.get("vegan").bool();
        boolean userIsVegetarian = userData.get("vegetarian").bool();
        if (userIsVegan)
            promptBuilder.append(", and ensuring all recipes are vegan-friendly");
        else if (userIsVegetarian) {
            if (vegan) {
                promptBuilder.append(", and ensuring all recipes are vegan-friendly");
            } else
                promptBuilder.append(", and ensuring all recipes are vegetarian-friendly");
        } else if (vegan || vegetarian) {
            promptBuilder.append(", and ensuring all recipes are");
            if (vegan) {
                promptBuilder.append(" vegan-friendly");
            } else {
                promptBuilder.append(" vegetarian-friendly");
            }
        }

        // Cuisine style
        if (cuisineStyle != null && !cuisineStyle.isEmpty()) {
            promptBuilder.append(String.format(", with a focus on %s cuisine", cuisineStyle));
        }

        // Cooking time
        if (cookingTime != null && !cookingTime.isEmpty()) {
            promptBuilder.append(String.format(", a maximum cooking time of %s", cookingTime));
        }

        // Flavor profile
        if (flavor != null && !flavor.isEmpty()) {
            promptBuilder.append(String.format(", a %s flavor profile", flavor));
        }

        // Occasion
        if (occasion != null && !occasion.isEmpty()) {
            promptBuilder.append(String.format(", and suitable for %s", occasion));
        }

        // Construct the final prompt
        return promptBuilder.toString();
    }

    /**
     * This method creates the instructions that define the format that the model
     * must use for returning the response
     * 
     * @return
     */
    private String generateSystemInstructions() {
        return "You're a helpful assistant, that just returns recipes names and their short description as a JSON with this format: {\"recipe1\": description of the recipe, \"recipe2\": description of the recipe...}";
    }

    /**
     * This method removes any leading or trailing characters that could be
     * generated before or after the JsonArray
     * 
     * @param openAIResult
     * @return
     */
    private String parseJsonArray(String openAIResult) {
        int startIndex = openAIResult.indexOf('[');
        int endIndex = openAIResult.lastIndexOf(']');

        if (startIndex != -1 && endIndex != -1) {
            return openAIResult.substring(startIndex, endIndex + 1);
        } else {
            throw new RuntimeException("Invalid JSON string format generated by OpenAI");
        }
    }

    private Map<String, Object> buildSuccessResponse(String response) {
        Map<String, Object> responseBody = new HashMap<>();
        responseBody.put("statusCode", 200);
        responseBody.put("body", response);
        return responseBody;
    }

    private String buildErrorResponse(String errorMessage) {
        return "Error occurred: " + errorMessage;
    }

}
