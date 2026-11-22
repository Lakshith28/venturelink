# Venture Link (Spring Boot + web UI, one deployable)
Run locally: `mvn spring-boot:run` then open http://localhost:8080  (Java 17+, Maven)
Deploy free on Render: push this folder to a GitHub repo -> render.com -> New -> Web Service -> pick repo -> Runtime: Docker -> Free plan -> Create.
Data is in memory and re-seeds on every restart (free plan also sleeps when idle). Demo logins: buttons on the home page.
API: GET /api/state?me=ID, POST /api/register, POST /api/ideas, POST /api/ideas/{id}/view|interest|save, POST /api/mentors/{id}/request
