# General rules

1.  When reasoning, you perform step-by-step thinking before you answer the question.
2.  If you speculate or predict something, always inform me.
3.  When asked for information you do not have, do not make up an answer; always be honest about not knowing.
4.  Document the project in such a way that an intern or LLM can easily pick it up and make progress on it.

## Rules for writing documentation

1.  When proposing an edit to a markdown file, indent any code snippets inside it with two spaces. Indentation levels 0 and 4 are not allowed.
2.  If a markdown code block is indented with any value other than 2 spaces, automatically fix it.

## When writing or planning code:

1.  Always write a test for the code you are changing
2.  Look for the simplest possible fix
3.  Don’t introduce new technologies without asking.
4.  Respect existing patterns and code structure
5.  Do not edit or delete comments.
6.  Don't remove debug logging code.
7.  When asked to generate code to implement a stub, do not delete docstrings
8.  When proposing sweeping changes to code, instead of proposing the whole thing at once and leaving "to implement" blocks, work through the proposed changes incrementally in cooperation with me


## IMPORTANT: Don't Forget

1.  When I add PLAN! at the end of my request, write a detailed technical plan on how you are going to implement my request, step by step, with short code snippets, but don't implement it yet, instead ask me for confirmation.
2.  When I add DISCUSS! at the end of my request, give me the ideas I've requested, but don't write code yet, instead ask me for confirmation.
3.  When I add STUB! at the end of my request, instead of implementing functions/methods, stub them and raise NotImplementedError.
4.  When I add EXPLORE! at the end of my request, do not give me your opinion immediately. Ask me questions first to make sure you fully understand the context before making suggestions.

# Guidelines for Clojure code

## Dependencies

1.  For working with JSON, use `metosin/jsonista` library over other libraries
2.  For creating an HTTP server, use `pedestal/pedestal` library and the Jetty server in that library.
3.  For working with SQL, use `com.seancorfield/next.jdbc` library
4.  For creating an HTTP client, use `clj-http/clj-http` library
5.  For message passing patterns, use `org.clojure/core.async` library
6.  For generative testing, use `test.check`.
7.  For running tests, use `io.github.cognitect-labs/test-runner`
8.  Write all specs, data as well as function specs, in a single `specs.clj` file.

## Testing

1. Write generative tests where possible.
