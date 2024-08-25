Overview
This Lambda function integrates with OpenAI's API to generate personalized recipe suggestions based on user preferences and nutritional requirements. It retrieves user data from DynamoDB and interacts with OpenAI's Chat API to create recipe suggestions. Additionally, it fetches configuration values such as the OpenAI API key, model, temperature, and max tokens from AWS SSM Parameter Store.

Prerequisites
AWS Account - This Lambda function requires access to AWS services like DynamoDB, SSM Parameter Store, and Lambda.
OpenAI API Key - Ensure you have an API key from OpenAI to access their services.
Java Environment - The code is written in Java and uses Spring WebClient and AWS SDKs.
Setup Instructions
1. AWS Services
DynamoDB Table: FitMyMacros
The table should store user data, including available ingredients, previous recipes, dietary preferences, and allergies.
SSM Parameter Store:
OpenAI-API_Key_Encrypted: Your encrypted OpenAI API key.
OpenAI-Model: The model name from OpenAI, e.g., gpt-4.
OpenAI-Model-Temperature: Model temperature for controlling creativity.
OpenAI-Max-Tokens: The maximum number of tokens for OpenAI's response.
2. Dependencies
This Lambda function uses the following dependencies:

Spring WebClient: For making non-blocking HTTP requests to OpenAI API.
AWS SDK for DynamoDB: To interact with the DynamoDB table.
AWS SDK for SSM: To retrieve parameters from SSM Parameter Store.
Jackson: For JSON serialization/deserialization.
Add these dependencies in your Maven or Gradle configuration.

3. Environment Configuration
Ensure your Lambda has the necessary IAM roles to:
Read from DynamoDB.
Access SSM Parameter Store.
Invoke external APIs.
Code Structure
Main Components
OpenAILambda:

Entry point of the Lambda function that handles incoming requests and orchestrates the process.
Parameter Retrieval:

Fetches OpenAI API Key, Model, Temperature, and Max Tokens from AWS SSM Parameter Store.
User Data Retrieval:

Retrieves user-specific data such as dietary preferences, allergies, and available ingredients from DynamoDB.
Prompt Generation:

Based on the user's data and preferences, it generates a prompt to be sent to OpenAI API.
OpenAI API Interaction:

Sends a POST request to OpenAI's API using WebClient and receives a response.
Response Parsing:

Processes and formats the response from OpenAI into a structured JSON format.
Key Methods
handleRequest: Handles incoming Lambda requests and processes them.
generatePrompt: Constructs the prompt based on user data and input parameters.
getUserData: Queries DynamoDB to retrieve user-specific data.
buildSuccessResponse: Formats the successful response to be returned to the client.
buildErrorResponse: Handles error responses and logs issues.
Running Locally
Set up your environment variables or an .env file with the necessary AWS and OpenAI credentials.
Use SAM CLI or LocalStack for local testing of AWS Lambda functions.
Deploy the Lambda function using AWS CLI or through the AWS Management Console.
