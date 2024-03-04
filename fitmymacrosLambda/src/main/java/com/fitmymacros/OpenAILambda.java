package com.fitmymacros;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;

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

    private static final String OPENAI_API_ENDPOINT = "https://api.openai.com/v1/completions";
    private static final String OPENAI_API_KEY_NAME = "OpenAI-API_Key_Encrypted";
    private final SsmClient ssmClient = SsmClient.builder().region(Region.EU_WEST_3).build();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private static String OPENAI_API_KEY;
    private static int MAX_PROMPT_LENGTH = 4096;
    private DynamoDbClient dynamoDbClient = DynamoDbClient.builder()
            .region(Region.EU_WEST_3)
            .build();

    @Override
    public Object handleRequest(Map<String, Object> input, Context context) {

        OPENAI_API_KEY = this.getOpenAIKey();
        String requestBody = this.generateRequestBody(input);
        HttpRequest request = this.generateHttpRequest(OPENAI_API_KEY, requestBody);

        try {
            HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return this.buildSuccessResponse(response.body().toString());
        } catch (Exception e) {
            return this.buildErrorResponse(e.getMessage());
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
        try {
            GetParameterRequest parameterRequest = GetParameterRequest.builder()
                    .name(OPENAI_API_KEY_NAME)
                    .withDecryption(true)
                    .build();

            GetParameterResponse parameterResponse = this.ssmClient.getParameter(parameterRequest);
            return parameterResponse.parameter().value();

        } catch (SsmException e) {
            System.exit(1);
        } finally {
            this.ssmClient.close();
        }
        return null;
    }

    /**
     * This method generates the request body that will be sent to the openai api
     * 
     * @return
     */
    private String generateRequestBody(Map<String, Object> input) {
        String userId = input.get("userId").toString();
        String measureUnit = input.get("measureUnit").toString();
        int calories = (Integer) input.get("calories");
        int protein = (Integer) input.get("protein");
        int carbs = (Integer) input.get("carbs");
        int fat = (Integer) input.get("fat");
        String satietyLevel = input.get("satietyLevel").toString();
        String precision = input.get("precision").toString(); // exact grams of protein, carbs and fat, or slight
                                                              // variation?
        boolean anyIngredientsMode = (Boolean) input.get("anyIngredientsMode");
        boolean expandIngredients = (Boolean) input.get("expandIngredients");
        boolean glutenFree = (Boolean) input.get("glutenFree");
        boolean vegan = (Boolean) input.get("vegan");
        boolean vegetarian = (Boolean) input.get("vegetarian");
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
        } finally {
            dynamoDbClient.close();
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

        StringBuilder promptBuilder = new StringBuilder();

        // Number of recipes to generate
        promptBuilder.append("Please generate 5 to 10 recipes");

        // Target nutritional goals
        promptBuilder.append(String.format(" with %s %d calories", precision, calories));
        promptBuilder.append(String.format(", containing %s %d%s of protein", precision, protein, measureUnit));
        promptBuilder.append(String.format(", containing %s %d%s of carbs", precision, protein, measureUnit));
        promptBuilder.append(String.format(", and %s %d%s of fat", precision, fat, measureUnit));

        // Desired satiety level
        promptBuilder.append(String.format(", ensuring they are %s", satietyLevel));

        // Details about available ingredients
        if (!anyIngredientsMode) {
            promptBuilder.append(", using ingredients available at home:");
            Map<String, AttributeValue> foodMap = userData.get("food").m();
            for (Map.Entry<String, AttributeValue> entry : foodMap.entrySet()) {
                String foodName = entry.getKey();
                int foodQuantity = Integer.parseInt(entry.getValue().n());
                promptBuilder.append(String.format(", %dg of %s", foodQuantity, foodName));
            }
        }

        // Exclude any allergens or intolerances
        List<AttributeValue> allergiesList = userData.get("allergies-intolerances").l();
        if (!allergiesList.isEmpty()) {
            promptBuilder.append(", avoiding ingredients such as");
            for (AttributeValue allergy : allergiesList) {
                String allergyName = allergy.m().get("S").s();
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

        // Expand ingredients option
        if (expandIngredients) {
            promptBuilder.append(", allowing for additional ingredients as needed");
        }

        // Cuisine style
        if (cuisineStyle != null && !cuisineStyle.isEmpty()) {
            promptBuilder.append(String.format(", with a focus on %s cuisine", cuisineStyle));
        }

        // Cooking time
        if (cookingTime != null && !cookingTime.isEmpty()) {
            promptBuilder.append(String.format(", with a cooking time of %s", cookingTime));
        }

        // Flavor profile
        if (flavor != null && !flavor.isEmpty()) {
            promptBuilder.append(String.format(", with a %s flavor profile", flavor));
        }

        // Occasion
        if (occasion != null && !occasion.isEmpty()) {
            promptBuilder.append(String.format(", suitable for %s", occasion));
        }

        // Construct the final prompt
        String prompt = promptBuilder.toString();

        // Limit the maximum length of the prompt to avoid exceeding OpenAI's limits
        if (prompt.length() > MAX_PROMPT_LENGTH) {
            prompt = prompt.substring(0, MAX_PROMPT_LENGTH);
        }

        int maxTokens = 500;
        String modelName = "gpt-3.5-turbo-instruct";
        return String.format("{\"prompt\": \"%s\", \"max_tokens\": %d, \"model\": \"%s\"}", prompt,
                maxTokens, modelName);
    }

    private HttpRequest generateHttpRequest(String openAiKey, String requestBody) {
        return HttpRequest.newBuilder()
                .uri(URI.create(OPENAI_API_ENDPOINT))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + openAiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
    }

    private Map<String, Object> buildSuccessResponse(String message) {
        Map<String, Object> responseBody = new HashMap<>();
        responseBody.put("statusCode", 200);
        responseBody.put("body", message);
        return responseBody;
    }

    private String buildErrorResponse(String errorMessage) {
        return "Error occurred: " + errorMessage;
    }

}
