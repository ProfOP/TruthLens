# Architecture

The browser calls Spring Boot under `/api/v1`. Spring Boot is the authorization, persistence, job orchestration, reporting, and storage boundary; it calls the private FastAPI service for inference. PostgreSQL persists users, analyses, evidence, claims, assets, explanations, reviews, jobs, and audit events. Uploaded and generated assets are stored under persistent local storage and served only through authorized Spring endpoints.

The implementation is intentionally conservative when a runtime is unavailable: a component reports unavailable rather than fabricating a prediction. Flyway now covers users, analyses, jobs, claims, evidence, reviews, assets, explanations, and audit events. JWT/BCrypt authentication is required for private analysis. Guest submission is an explicit gap. Ownerless legacy rows are retained and are not exposed on user history or result APIs.

