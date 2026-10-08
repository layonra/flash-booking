# Flash Booking

Implementação do desafio técnico **Engenheiro de Backend Sênior / Especialista — Reserva de Ingressos (Flash Booking)**: uma API de reserva de ingressos com garantia de não overselling, idempotência persistida e expiração automática de reservas.

A suíte tem 47 testes (unitários + integração com PostgreSQL/Testcontainers + testes concorrentes de overselling e idempotência), além de cenários validados manualmente contra a aplicação rodando em Docker.

## Requisitos

Requisitos funcionais e não funcionais definidos no enunciado oficial do desafio: [`Case BackEnd.pdf`](Case%20BackEnd.pdf). A implementação cobre:

- `POST /events`
- `GET /events/{id}`
- `POST /events/{id}/reservations`
- `GET /reservations/{id}`
- `DELETE /reservations/{id}`
- múltiplas instâncias
- nunca permitir overselling
- expiração automática de reservas pendentes
- idempotência
- consistência eventual permitida para disponibilidade
- tratamento explícito de erros
- Docker Compose
- testes automatizados
- README

## Stack

- Java 21
- Spring Boot 3.5
- Spring Web
- Spring Data JPA
- PostgreSQL
- Flyway
- JUnit 5
- Mockito
- Testcontainers
- Docker Compose (imagem multi-stage: build Maven no container, JRE na final)

## Arquitetura

A aplicação é stateless:

```text
                    ┌──────────────┐
                    │    Client    │
                    └──────┬───────┘
                           │
             ┌─────────────▼─────────────┐
             │      Spring Boot API      │
             │       stateless           │
             └─────────────┬─────────────┘
                           │
             ┌─────────────▼─────────────┐
             │        PostgreSQL         │
             │       source of truth     │
             └───────────────────────────┘
```

Várias instâncias da API podem executar simultaneamente porque nenhuma informação crítica de concorrência fica na memória da aplicação.

## 1. PostgreSQL como source of truth

A escolha do PostgreSQL é principalmente motivada pela garantia de não haver overselling.

O estoque é protegido por:

- transações;
- atualização atômica;
- constraints;
- concorrência transacional do banco.

Redis e Kafka não fazem parte desta implementação porque não são necessários para cumprir o caso.

## 2. Controle de estoque

A operação crítica é:

```sql
UPDATE events
SET available_quantity = available_quantity - :quantity
WHERE id = :eventId
  AND available_quantity >= :quantity;
```

O retorno `1` significa que o estoque foi reservado.

O retorno `0` significa que a condição não pôde ser satisfeita.

Isso evita:

```text
SELECT available_quantity
        ↓
verifica quantidade
        ↓
UPDATE
```

porque duas instâncias poderiam observar o mesmo saldo antes de atualizar.

## 3. Transação

A criação da reserva ocorre numa única transação:

```text
BEGIN

1. verificar idempotência
2. verificar evento
3. decrementar estoque atomicamente
4. criar reservation
5. persistir Idempotency-Key

COMMIT
```

Qualquer erro faz rollback.

O `@Transactional` define o boundary transacional na aplicação; PostgreSQL fornece as propriedades transacionais.

## 4. Idempotência

A tabela:

```text
idempotency_keys
----------------
key_value       PK
request_hash
reservation_id
created_at
```

A chave é global e persistida no banco.

### Retry normal

```text
request
   ↓
Idempotency-Key = ABC
   ↓
reserva criada
```

Retry:

```text
Idempotency-Key = ABC
   ↓
registro encontrado
   ↓
mesmo hash
   ↓
retorna mesma reservation
```

### Payload diferente

```text
ABC + quantity=2
```

seguido de:

```text
ABC + quantity=5
```

resulta em:

```text
409 IDEMPOTENCY_KEY_REUSED
```

### Requisições concorrentes

A chave possui `PRIMARY KEY`, portanto duas transações não podem persistir a mesma chave.

O `IdempotencyKeyRepository` faz o insert com um `INSERT` nativo (`insertNew`). Isso é essencial: a persistência por `save()`/`saveAndFlush()` com chave atribuída pelo caller passa pelo caminho de merge do JPA e, em caso de colisão concorrente, atualizava silenciosamente a linha existente em vez de falhar — mascarando o conflito. O `INSERT` nativo reproduz a violação de `PRIMARY KEY` dentro da transação. A requisição que perder a corrida recebe:

```text
409 IDEMPOTENCY_CONCURRENT_REQUEST
```

e deve fazer retry.

Um retry posterior encontra a chave já persistida e retorna a reserva vencedora.

Após o retry, a resposta é idêntica à original — inclusive quando a reserva já terminou o ciclo de vida: um replay de uma reserva expirada ou cancelada retorna o estado terminal (`EXPIRED`/`CANCELLED`), nunca uma nova reserva.

Uma chave reutilizada com payload diferente é rejeitada com:

```text
409 IDEMPOTENCY_KEY_REUSED
```

A requisição sem header recebe:

```text
409 IDEMPOTENCY_KEY_REQUIRED
```

Esse comportamento é uma escolha simples e adequada ao escopo do desafio. Uma evolução poderia implementar retry transacional automático.

## 5. Expiração

Reservas começam como:

```text
PENDING
```

e recebem:

```text
expires_at
```

O scheduler procura reservas vencidas.

A transição é condicional:

```sql
UPDATE reservations
SET status = 'EXPIRED'
WHERE id = :id
AND status = 'PENDING';
```

Somente quem conseguir executar `PENDING -> EXPIRED` devolve o estoque.

Isso torna a operação segura quando existem múltiplas instâncias executando o scheduler.

## 6. Cancelamento

Cancelamento utiliza o mesmo mecanismo:

```sql
UPDATE reservations
SET status = 'CANCELLED'
WHERE id = :id
AND status = 'PENDING';
```

Se a atualização ocorrer:

```text
PENDING
   ↓
CANCELLED
   ↓
devolve estoque
```

Se uma expiração ganhar a corrida, o cancelamento não devolve estoque.

Isso evita double release.

## 7. Disponibilidade

`GET /events/{id}` consulta `available_quantity` diretamente no PostgreSQL.

O requisito permite consistência eventual, mas não exige que ela seja introduzida.

Uma evolução possível:

```text
PostgreSQL
    │
    ├── source of truth
    │
    └── eventos
          ↓
       Redis
          ↓
   GET /events/{id}
```

Redis seria somente um read model/cache.

A reserva continuaria a ser decidida pelo PostgreSQL.

## 8. Estados

Os estados de reserva são:

```text
PENDING
CANCELLED
EXPIRED
```

Não existe `CONFIRMED` porque o desafio não define pagamento ou confirmação.

## 9. Testes

47 testes em duas suítes, separadas por pacote:

```text
src/test/java/com/cielo/booking/
├── unit/
│   ├── ReservationServiceUnitTest           (18)
│   ├── EventServiceUnitTest                 (3)
│   ├── ReservationExpirationServiceUnitTest (2)
│   └── GlobalExceptionHandlerUnitTest       (1)
└── integration/
    ├── support/
    │   └── AbstractIntegrationTest          (container PostgreSQL compartilhado)
    ├── FlywaySchemaIntegrationTest          (3)
    ├── RepositoryIntegrationTest            (6)
    ├── EndToEndFlowIntegrationTest          (5)
    ├── ExpirationAndCancellationIntegrationTest (4)
    ├── ConcurrencyIntegrationTest           (2)
    └── ScheduledExpirationIntegrationTest   (3, contexto próprio com scheduler rápido)
```

### Unitário

Serviços testados isolados com Mockito: regras de idempotência, guarda de estoque, transições CAS de estado, expiração resiliente e handlers de exceção.

### Integração

Os testes utilizam PostgreSQL real através do Testcontainers.

Isso é importante porque os principais ‘bugs’ deste desafio são relacionados a:

- transações;
- constraints;
- concorrência;
- comportamento real do banco.

Mocks não conseguem validar essas propriedades.

Cobertura de integração:

- **Flyway/Schema**: persistência round-trip, defaults, constraints rejeitando quantity inválida;
- **Repositório**: SQL das queries `@Modifying` — débito guardado, crédito limitado pela capacidade, CAS `PENDING -> status`, query de candidatas à expiração;
- **Fluxo ponta a ponta**: criar evento, reservar, idempotência, cancelar, expirar — via MockMvc HTTP;
- **Concorrência**: overselling (10 requisições × 20 ingressos, capacity 100) e colisão de Idempotency-Key com 8 workers simultâneos.

### Overselling

O teste:

```text
capacity = 100

10 requisições concorrentes
cada uma = 20 ingressos
```

Resultado esperado:

```text
5 sucessos
5 conflitos
0 overselling

available_quantity = 0
reservado = 100
```

### Idempotência concorrente

8 requisições simultâneas com a mesma `Idempotency-Key` resultam em exatamente 1 reserva e 1 débito de estoque.

### Expiração

Existe teste garantindo:

```text
PENDING
   ↓
EXPIRED
   ↓
estoque devolvido
```

e que executar a expiração novamente não devolve o estoque duas vezes.

`ScheduledExpirationIntegrationTest` valida o scheduler de verdade, com Awaitility e um contexto com intervalo de 200ms: reservas vencidas viram `EXPIRED` sem intervenção e estoque volta.

## 10. Pré-requisitos

- **Só Docker** para rodar a aplicação (`docker compose up --build` compila o projeto dentro de um build stage — não requer JDK nem Maven na máquina);
- JDK 21 + Maven para desenvolver e rodar a suíte de testes localmente;
- Docker é obrigatório para a suíte de integração (Testcontainers).

## 11. Executando

### Testes

É necessário Docker ativo para os testes Testcontainers:

```bash
mvn test
```

### Aplicação

```bash
docker compose up --build
```

O `Dockerfile` é multi-stage: o estágio de build compila o jar dentro do container (Maven + JDK 21) e a imagem final contém apenas o JRE. Nenhum artefato pré-compilado é necessário.

Alternativa sem Docker (requer JDK 21 e um PostgreSQL acessível):

```bash
mvn clean package
SPRING_DATASOURCE_URL='jdbc:postgresql://localhost:5432/flash_booking' \
java -jar target/flash-booking-2.0.0.jar
```

API:

```text
http://localhost:8080
```

### Configuração

| Variável | Default | Descrição |
|----------|---------|-----------|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/flash_booking` | JDBC do PostgreSQL |
| `SPRING_DATASOURCE_USERNAME` | `postgres` | usuário do banco |
| `SPRING_DATASOURCE_PASSWORD` | `postgres` | senha do banco |
| `SERVER_PORT` | `8080` | porta HTTP |
| `RESERVATION_EXPIRATION_SECONDS` | `300s` | TTL de reservas `PENDING` |
| `RESERVATION_EXPIRATION_SCHEDULER_MS` | `1s` | intervalo do scheduler de expiração |
| `POSTGRES_HOST_PORT` | `5432` | porta do Postgres exposta pelo Docker Compose |

Exemplo: rodar o jar localmente contra o Postgres do Docker Compose em porta alternativa:

```bash
POSTGRES_HOST_PORT=5433 docker compose up -d postgres
SPRING_DATASOURCE_URL='jdbc:postgresql://localhost:5433/flash_booking' \
java -jar target/flash-booking-2.0.0.jar
```

## 12. Teste manual

Criar evento:

```bash
curl -X POST http://localhost:8080/events \
  -H 'Content-Type: application/json' \
  -d '{"name":"Rock Festival","capacity":100}'
```

Consultar:

```bash
curl http://localhost:8080/events/{eventId}
```

Reservar:

```bash
curl -X POST http://localhost:8080/events/{eventId}/reservations \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-001' \
  -d '{"quantity":2}'
```

Repetir exatamente a mesma requisição:

```bash
curl -X POST http://localhost:8080/events/{eventId}/reservations \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-001' \
  -d '{"quantity":2}'
```

A segunda chamada deve retornar a mesma reserva.

## 13. Contrato de erros

Toda a resposta de erro tem o mesmo contrato:

```json
{
  "code": "RESERVATION_NOT_FOUND",
  "message": "Reservation not found.",
  "timestamp": "2026-10-08T09:46:57.91387-03:00",
  "path": "/reservations/f967d713-b703-4cee-8574-83329ba50cae"
}
```

| HTTP | code | Quando |
|------|------|--------|
| 400 | `VALIDATION_ERROR` | Bean Validation falha (nome do evento em branco, capacity ≤ 0, quantity ≤ 0) |
| 400 | `MALFORMED_REQUEST` | corpo não é JSON válido |
| 400 | `INVALID_PARAMETER` | path/param não conversível (ex.: id que não é UUID) |
| 404 | `EVENT_NOT_FOUND` | evento inexistente |
| 404 | `RESERVATION_NOT_FOUND` | reserva inexistente |
| 409 | `IDEMPOTENCY_KEY_REQUIRED` | `POST /reservations` sem header `Idempotency-Key` |
| 409 | `IDEMPOTENCY_KEY_REUSED` | chave já usada com payload diferente |
| 409 | `IDEMPOTENCY_KEY_TOO_LONG` | chave com mais de 255 caracteres |
| 409 | `IDEMPOTENCY_CONCURRENT_REQUEST` | duas requisições simultâneas com a mesma chave; retry seguro |
| 409 | `IDEMPOTENCY_STATE_INVALID` | registro de idempotência aponta para reserva inexistente (nunca esperado) |
| 409 | `INSUFFICIENT_INVENTORY` | estoque insuficiente para a quantity |
| 409 | `RESERVATION_NOT_CANCELLABLE` | cancelamento de reserva que já não está `PENDING` |
| 500 | `INTERNAL_ERROR` | erro inesperado; detalhado no log da aplicação |

## 14. Trade-offs

### Escolhidos

- PostgreSQL como source of truth;
- API stateless;
- atomic update para estoque;
- transações;
- idempotência persistida;
- scheduler simples;
- JPA para CRUD;
- SQL explícito nas operações críticas;
- Testcontainers para validar comportamento real do banco.

### Não adicionados

- Redis;
- Kafka;
- locks distribuídos;
- CQRS;
- event sourcing.

Essas tecnologias podem ser úteis numa evolução, mas não são necessárias para o problema apresentado.

## 15. Próximas evoluções

1. retry transacional automático para colisão de idempotência;
2. estratégia de retenção/TTL das chaves;
3. observabilidade;
4. métricas de reservas e conflitos;
5. rate limiting;
6. Redis como read model;
7. Outbox Pattern para publicação confiável de eventos;
8. particionamento/otimização caso o volume de reservas cresça significativamente.

## Estrutura

```text
src/
├── main/
│   ├── java/com/cielo/booking/
│   │   ├── controller/
│   │   ├── domain/
│   │   ├── dto/
│   │   ├── exception/
│   │   ├── repository/
│   │   └── service/
│   └── resources/
│       ├── application.yml
│       └── db/migration/          
└── test/
    └── java/com/cielo/booking/
        ├── unit/                  
        └── integration/           
```
