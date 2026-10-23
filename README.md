# RefundGuard.AI

**Explainable Refund Risk Scoring and Refund Monitoring System**

RefundGuard.AI is a software project focused on refund monitoring, risk assessment, and refund review workflows. It aims to help administrators and reviewers identify potentially anomalous refund activity, understand the factors contributing to a risk score, and monitor refund processing through a centralized interface.

## Project Objectives

- Assess refund risk using a score and explainable contributing factors.
- Monitor refund processing and reconciliation statuses.
- Support structured refund review workflows.
- Provide role-based access for authorized users.
- Present relevant information to support review and operational decisions.

## Key Functional Areas

- **Refund Risk Scoring:** Represent refund risk using a numerical score and risk classification, based on the implemented scoring rules.
- **Risk Explanations:** Help reviewers understand which factors contribute to a refund's risk assessment.
- **Refund Monitoring:** Track refund processing and reconciliation information.
- **Review Workflow:** Support reviewer activities and access controls where implemented.
- **Risk Analytics:** Provide an overview of refund risk information where available in the application.

*The availability and completeness of individual features depend on the current implementation. Refer to the technical documentation for implementation details and known limitations.*

## Technology Stack

- **Frontend:** React
- **Backend:** Java and Spring Boot
- **Database:** Oracle Database
- **Environment:** Docker Compose
- **Version Control:** Git and GitHub

## Getting Started

### Prerequisites

Install the tools required by your project configuration, such as:

- Git
- Java Development Kit (JDK) compatible with the project
- Maven, if required by the backend
- Node.js and npm
- Docker Desktop with Docker Compose
- Oracle Database service configured for the application

### Configuration

1. Review the project's environment and configuration files.
2. Configure the required environment variables locally.
3. Create your local `.env` file using the variable names documented by the project.
4. Ensure that database credentials and other secrets are not committed to GitHub.

**Security note:** Never publish real passwords, API keys, access tokens, or private connection strings.

### Run the Application

For Docker-based setup, consult the Docker setup instructions in [PROJECT_DOCUMENTATION.md](PROJECT_DOCUMENTATION.md).

Follow the frontend and backend setup instructions for running the application independently. The required commands, ports, and environment variables depend on the current project configuration.

## Documentation

The consolidated technical document is available here:

**[Read the Complete Project Documentation](PROJECT_DOCUMENTATION.md)**

It brings together the project's risk-scoring implementation, reviewer implementation report, Docker setup guidance, and enhancement report.

## Security

- Never commit `.env` files containing real credentials.
- Do not publish passwords, API keys, access tokens, or sensitive connection strings.
- Enforce authorization checks on the backend rather than relying only on frontend visibility.
- Apply appropriate access controls when handling refund and payment-related data.

## Contributors

1- Vibhore Sharma 
2- Chetan Dutt
3- Naman Yadav
4- Ankur Mishra
5- Shreya Yadav