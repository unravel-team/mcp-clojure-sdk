# General rules

- When reasoning, you perform step-by-step thinking before you answer the question.
- If you speculate or predict something, always inform me.
- When asked for information you do not have, do not make up an answer; always be honest about not knowing.
- Document the project in such a way that an intern or LLM can easily pick it up and make progress on it.

## Rules for writing documentation

- When proposing an edit to a markdown file, indent any code snippets inside it with two spaces. Indentation levels 0 and 4 are not allowed.
- If a markdown code block is indented with any value other than 2 spaces, automatically fix it.

## When writing or planning code:

- Always write a test for the code you are changing
- Look for the simplest possible fix
- Don’t introduce new technologies without asking.
- Respect existing patterns and code structure
- Do not edit or delete comments.
- Don't remove debug logging code.
- When asked to generate code to implement a stub, do not delete docstrings
- When proposing sweeping changes to code, instead of proposing the whole thing at once and leaving "to implement" blocks, work through the proposed changes incrementally in cooperation with me

## IMPORTANT: Don't Forget

- When I add PLAN! at the end of my request, write a detailed technical plan on how you are going to implement my request, step by step, with short code snippets, but don't implement it yet, instead ask me for confirmation.
- When I add DISCUSS! at the end of my request, give me the ideas I've requested, but don't write code yet, instead ask me for confirmation.
- When I add STUB! at the end of my request, instead of implementing functions/methods, stub them and raise NotImplementedError.
- When I add EXPLORE! at the end of my request, do not give me your opinion immediately. Ask me questions first to make sure you fully understand the context before making suggestions.

# Guidelines for Clojure code

## Build/Test Commands

- Build: `make build`
- Install locally: `make install`
- Run all tests: `make test`
- Full check: `make check` (runs linters and formatting checks)
- Single test: `clojure -X:test :only your.test.ns/test-name`
- Format the code: `make format`. Uses zprint for formatting.

## Dependencies and Code Style

- Use `metosin/jsonista` for working with JSON
- Use `pedestal/pedestal` for creating an HTTP servers. Use the Jetty server.
- Use `com.seancorfield/next.jdbc` for working with SQL
- Use `clj-http/clj-http` for creating an HTTP client
- Use `org.clojure/core.async` for message passing and async operations
- Use `test.check` for generative testing. Prefer writing generative tests.
- Use `io.github.cognitect-labs/test-runner` as a test-runner
- Write all specs, data as well as function specs, in a single `specs.clj` file.

## Error Handling

- Use `ex-info` for exceptions with detailed context
- Include error codes and descriptive messages in responses
- Maintain existing debug logging code
- Set `:is-error` flag in tool responses when errors occur
