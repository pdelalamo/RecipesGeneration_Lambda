package com.fitmymacros;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.theokanning.openai.completion.CompletionRequest;
import com.theokanning.openai.service.OpenAiService;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import software.amazon.awssdk.services.ssm.model.SsmException;

public class OpenAILambda implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final String OPENAI_API_KEY_NAME = "OpenAI-API_Key_Encrypted";
    private static final String OPENAI_MODEL_NAME = "OpenAI-Model";
    private final SsmClient ssmClient;
    private String OPENAI_AI_KEY;
    private String OPENAI_MODEL;
    private final String RESULT_TABLE_NAME = "FitMyMacros_OpenAI_Results";
    private final DynamoDbClient dynamoDbClient;

    public OpenAILambda() {
        ssmClient = SsmClient.builder().region(Region.EU_WEST_3).build();
        this.dynamoDbClient = DynamoDbClient.builder().region(Region.EU_WEST_3).build();
        this.OPENAI_AI_KEY = this.getOpenAIKeyFromParameterStore();
        this.OPENAI_MODEL = this.getOpenAIModelFromParameterStore();
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        try {
            String opId = generateUniqueIdentifier();

            CompletableFuture.runAsync(() -> {
                try {
                    String prompt = generatePrompt(input.getQueryStringParameters());
                    OpenAiService service = new OpenAiService(OPENAI_AI_KEY, Duration.ofSeconds(50));
                    CompletionRequest completionRequest = CompletionRequest.builder()
                            .prompt(prompt)
                            .model(OPENAI_MODEL)
                            .maxTokens(3000)
                            .echo(true)
                            .build();
                    String openAIResponse = service.createCompletion(completionRequest).getChoices().get(0).getText()
                            .replace(prompt, "");
                    System.out.println("openAIResponse: " + openAIResponse);
                    this.putItemInDynamoDB(opId, openAIResponse);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });

            return buildSuccessResponse(opId);

        } catch (Exception e) {
            return this.buildErrorResponse(e.getMessage());
        }
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

    /**
     * This method takes the generated opId, and the result of the call to openAI,
     * and creates and element that will be stored in dynamoDB, for its future
     * retrieval by another lambda. It creates a ttl attribute, that represents the
     * current time +5mins, and after that time, dynamoDB will delete the element
     * from the table
     * 
     * @param eventData
     */
    private void putItemInDynamoDB(String opId, String openAIResult) {
        AttributeValue opIdAttributeValue = AttributeValue.builder().s(opId).build();
        AttributeValue openAIResultAttributeValue = AttributeValue.builder().s(openAIResult).build();
        AttributeValue ttlAttributeValue = AttributeValue.builder()
                .s(Long.toString((System.currentTimeMillis() / 1000L) + (5 * 60))).build();

        Map<String, AttributeValue> itemAttributes = new HashMap<>();
        itemAttributes.put("opId", opIdAttributeValue);
        itemAttributes.put("openAIResult", openAIResultAttributeValue);
        itemAttributes.put("ttl", ttlAttributeValue);
        PutItemRequest request = PutItemRequest.builder()
                .tableName(this.RESULT_TABLE_NAME)
                .item(itemAttributes)
                .build();

        System.out.println("I put the item");
        dynamoDbClient.putItem(request);
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
        String userId = input.get("userId").toString();
        String measureUnit = input.get("measureUnit").toString();
        int calories = Integer.parseInt(input.get("calories"));
        int protein = Integer.parseInt(input.get("protein"));
        int carbs = Integer.parseInt(input.get("carbs"));
        int fat = Integer.parseInt(input.get("fat"));
        String satietyLevel = input.get("satietyLevel").toString();
        String precision = input.get("precision").toString(); // exact grams of protein, carbs and fat, or slight
                                                              // variation?
        boolean anyIngredientsMode = Boolean.parseBoolean(input.get("anyIngredientsMode"));
        boolean expandIngredients = Boolean.parseBoolean(input.get("expandIngredients"));
        boolean glutenFree = Boolean.parseBoolean(input.get("glutenFree"));
        boolean vegan = Boolean.parseBoolean(input.get("vegan"));
        boolean vegetarian = Boolean.parseBoolean(input.get("vegetarian"));
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
        promptBuilder.append(String.format(
                "Please generate 5 recipes, and return the response as JSON array (please, always return a unique valid JSON array, with no title for each element, just valid JSON elements), where each of the recipes is a JSON inside the JSON array. The JSON array must follow this structure:  %s",
                this.generateResponseTemplate()));

        // Target nutritional goals
        promptBuilder.append(String.format(" The recipes should have %s %d calories", precision, calories));
        promptBuilder.append(String.format(", containing %s %d%s of protein", precision, protein, measureUnit));
        promptBuilder.append(String.format(", containing %s %d%s of carbs", precision, protein, measureUnit));
        promptBuilder.append(String.format(", and %s %d%s of fat", precision, fat, measureUnit));

        // Desired satiety level
        promptBuilder.append(String.format(", ensuring they are %s", satietyLevel));

        // Details about available ingredients
        if (!anyIngredientsMode) {
            promptBuilder.append(", using just ingredients available at home:");
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
        // if (prompt.length() > MAX_PROMPT_LENGTH) {
        // prompt = prompt.substring(0, MAX_PROMPT_LENGTH);
        // }
        return prompt;
    }

    /**
     * This method returns a String representation of the desired format for the
     * response generated by openAI
     * 
     * @return
     */
    private String generateResponseTemplate() {
        return "[\\n"
                +
                "{\\n"
                +
                " \"recipeName\": \"\",\\n"
                +
                " \"cookingTime\": \"\",\\n"
                +
                " \"caloriesAndMacros\": {\\n"
                +
                " \"calories\": \"\",\\n"
                +
                " \"protein\": \"\",\\n"
                +
                " \"carbs\": \"\",\\n"
                +
                " \"fat\": \"\"\\n"
                +
                " },\\n"
                +
                " \"ingredientsAndQuantities\": [\\n"
                +
                " { \"ingredient\": \"\", \"quantity\": \"\" },\\n"
                +
                " { \"ingredient\": \"\", \"quantity\": \"\" }\\n"
                +
                " ],\\n"
                +
                " \"cookingProcess\": [\\n"
                +
                " \"Step 1\",\\n"
                +
                " \"Step 2\"\\n"
                +
                " ]\\n"
                +
                "},\\n"
                +
                "."
                +
                "."
                +
                "."
                +
                "]\\n";
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
