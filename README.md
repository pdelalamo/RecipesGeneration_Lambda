# Overview

This Lambda function integrates with OpenAI's API to generate personalized recipe suggestions based on user preferences and nutritional requirements. The function retrieves user data from DynamoDB and interacts with OpenAI's Chat API to create tailored recipe suggestions. Additionally, it fetches configuration values such as the OpenAI API key, model, temperature, and max tokens from AWS SSM Parameter Store.

## Prerequisites

Before running this Lambda function, ensure you have the following prerequisites:

- **AWS Account**: The function requires access to AWS services, including DynamoDB, SSM Parameter Store, and Lambda.
- **OpenAI API Key**: An API key from OpenAI is required to access their services.
- **Java Environment**: The code is written in Java and utilizes Spring WebClient and AWS SDKs.

## Setup Instructions

### AWS Services

- **DynamoDB Table: `FitMyMacros`**
  - This table should store user data, including available ingredients, previous recipes, dietary preferences, and allergies.

- **SSM Parameter Store**:
  - `OpenAI-API_Key_Encrypted`: The encrypted OpenAI API key.
  - `OpenAI-Model`: The model name from OpenAI (e.g., `gpt-4`).
  - `OpenAI-Model-Temperature`: The model temperature for controlling creativity.
  - `OpenAI-Max-Tokens`: The maximum number of tokens for OpenAI's response.

### Dependencies

This Lambda function uses the following dependencies:

- **Spring WebClient**: For making non-blocking HTTP requests to the OpenAI API.
- **AWS SDK for DynamoDB**: To interact with the DynamoDB table.
- **AWS SDK for SSM**: To retrieve parameters from the SSM Parameter Store.
- **Jackson**: For JSON serialization/deserialization.

Make sure to add these dependencies to your Maven or Gradle configuration.

### Environment Configuration

Ensure that your Lambda has the necessary IAM roles to:

- Read from DynamoDB.
- Access SSM Parameter Store.
- Invoke external APIs.

## Code Structure

### Main Components

- **OpenAILambda**:  
  The entry point of the Lambda function that handles incoming requests and orchestrates the entire process.

- **Parameter Retrieval**:  
  Retrieves the OpenAI API Key, Model, Temperature, and Max Tokens from AWS SSM Parameter Store.

- **User Data Retrieval**:  
  Fetches user-specific data such as dietary preferences, allergies, and available ingredients from DynamoDB.

- **Prompt Generation**:  
  Based on the user's data and preferences, a prompt is generated to be sent to the OpenAI API.

- **OpenAI API Interaction**:  
  Sends a POST request to OpenAI's API using WebClient and processes the response.

- **Response Parsing**:  
  Processes and formats the response from OpenAI into a structured JSON format.

### Key Methods

- **handleRequest**:  
  Handles incoming Lambda requests and processes them.

- **generatePrompt**:  
  Constructs the prompt based on user data and input parameters.

- **getUserData**:  
  Queries DynamoDB to retrieve user-specific data.

- **buildSuccessResponse**:  
  Formats the successful response to be returned to the client.

- **buildErrorResponse**:  
  Handles error responses and logs issues.

## Running Locally

To run the Lambda function locally:

1. Set up your environment variables or create a `.env` file containing the necessary AWS and OpenAI credentials.
2. Use **SAM CLI** or **LocalStack** for local testing of AWS Lambda functions.
3. Deploy the Lambda function using **AWS CLI** or through the **AWS Management Console**.
