.PHONY: install-antq install-kondo-configs install-zprint-config install-gitignore repl-enrich repl check-cljkondo check-tagref check-zprint-config check-zprint check test test-all test-coverage upgrade-libs build serve deploy clean-projects clean

HOME := $(shell echo $$HOME)
HERE := $(shell echo $$PWD)
CLOJURE_SOURCES := $(shell find . -name '**.clj')

# Set bash instead of sh for the @if [[ conditions,
# and use the usual safety flags:
SHELL = /bin/bash -Eeu

.DEFAULT_GOAL := repl

help:    ## A brief explanation of everything you can do
	@awk '/^[a-zA-Z0-9_-]+:.*##/ { \
		printf "%-25s # %s\n", \
		substr($$1, 1, length($$1)-1), \
		substr($$0, index($$0,"##")+3) \
	}' $(MAKEFILE_LIST)

# The Clojure CLI aliases that will be selected for main options for `repl`.
# Feel free to upgrade this, or to override it with an env var named DEPS_MAIN_OPTS.
# Expected format: "-M:alias1:alias2"
DEPS_MAIN_OPTS ?= "-M:dev:test:logs-dev:cider-storm"

repl:    ## Launch a REPL using the Clojure CLI
	clojure $(DEPS_MAIN_OPTS);

# The enrich-classpath version to be injected.
# Feel free to upgrade this.
ENRICH_CLASSPATH_VERSION="1.19.3"

# Create and cache a `clojure` command. deps.edn is mandatory; the others are optional but are taken into account for cache recomputation.
# It's important not to silence with step with @ syntax, so that Enrich progress can be seen as it resolves dependencies.
.enrich-classpath-repl: Makefile deps.edn $(wildcard $(HOME)/.clojure/deps.edn) $(wildcard $(XDG_CONFIG_HOME)/.clojure/deps.edn)
	cd $$(mktemp -d -t enrich-classpath.XXXXXX); clojure -Sforce -Srepro -J-XX:-OmitStackTraceInFastThrow -J-Dclojure.main.report=stderr -Sdeps '{:deps {mx.cider/tools.deps.enrich-classpath {:mvn/version $(ENRICH_CLASSPATH_VERSION)}}}' -M -m cider.enrich-classpath.clojure "clojure" "$(HERE)" "true" $(DEPS_MAIN_OPTS) | grep "^clojure" > $(HERE)/$@

# Launches a repl, falling back to vanilla Clojure repl if something went wrong during classpath calculation.
repl-enrich: .enrich-classpath-repl    ## Launch a repl enriched with Java source code paths
	@if grep --silent "^clojure" .enrich-classpath-repl; then \
		echo "Executing: $$(cat .enrich-classpath-repl)" && \
		eval $$(cat .enrich-classpath-repl); \
	else \
		echo "Falling back to Clojure repl... (you can avoid further falling back by removing .enrich-classpath-repl)"; \
		clojure $(DEPS_MAIN_OPTS); \
	fi

.clj-kondo:
	mkdir .clj-kondo

install-kondo-configs: .clj-kondo    ## Install clj-kondo configs for all the currently installed deps
	clj-kondo --lint "$$(clojure -A:dev:test:cider:build -Spath)" --copy-configs --skip-lint

check-zprint-config:
	@echo "Checking (HOME)/.zprint.edn..."
	@if [ ! -f "$(HOME)/.zprint.edn" ]; then \
		echo "Error: ~/.zprint.edn not found"; \
		echo "Please create ~/.zprint.edn with the content: {:search-config? true}"; \
		exit 1; \
	fi
	@if ! grep -q "search-config?" "$(HOME)/.zprint.edn"; then \
		echo "Warning: ~/.zprint.edn might not contain required {:search-config? true} setting"; \
		echo "Please ensure this setting is present for proper functionality"; \
		exit 1; \
	fi

.zprint.edn:
	@echo "Creating .zprint.edn..."
	@echo '{:fn-map {"with-context" "with-meta"}, :map {:indent 0}}' > $@

.dir-locals.el:
	@echo "Creating .dir-locals.el..."
	@echo ';;; Directory Local Variables         -*- no-byte-compile: t; -*-' > $@
	@echo ';;; For more information see (info "(emacs) Directory Variables")' >> $@
	@echo '((clojure-dart-ts-mode . ((apheleia-formatter . (zprint))))' >> $@
	@echo ' (clojure-jank-ts-mode . ((apheleia-formatter . (zprint))))' >> $@
	@echo ' (clojure-mode . ((apheleia-formatter . (zprint))))' >> $@
	@echo ' (clojure-ts-mode . ((apheleia-formatter . (zprint))))' >> $@
	@echo ' (clojurec-mode . ((apheleia-formatter . (zprint))))' >> $@
	@echo ' (clojurec-ts-mode . ((apheleia-formatter . (zprint))))' >> $@
	@echo ' (clojurescript-mode . ((apheleia-formatter . (zprint))))' >> $@
	@echo ' (clojurescript-ts-mode . ((apheleia-formatter . (zprint)))))' >> $@

install-zprint-config: check-zprint-config .zprint.edn .dir-locals.el    ## Install configuration for using the zprint formatter
	@echo "zprint configuration files created successfully."

.gitignore:
	@echo "Creating a .gitignore file"
	@echo '# Artifacts' > $@
	@echo '**/classes' >> $@
	@echo '**/target' >> $@
	@echo '**/.artifacts' >> $@
	@echo '**/.cpcache' >> $@
	@echo '**/.DS_Store' >> $@
	@echo '**/.gradle' >> $@
	@echo 'logs/' >> $@
	@echo '' >> $@
	@echo '# 12-factor App Configuration' >> $@
	@echo '.envrc' >> $@
	@echo '' >> $@
	@echo '# User-specific stuff' >> $@
	@echo '.idea/**/workspace.xml' >> $@
	@echo '.idea/**/tasks.xml' >> $@
	@echo '.idea/**/usage.statistics.xml' >> $@
	@echo '.idea/**/shelf' >> $@
	@echo '.idea/**/statistic.xml' >> $@
	@echo '.idea/dictionaries/**' >> $@
	@echo '.idea/libraries/**' >> $@
	@echo '' >> $@
	@echo '# File-based project format' >> $@
	@echo '*.iws' >> $@
	@echo '*.ipr' >> $@
	@echo '' >> $@
	@echo '# Cursive Clojure plugin' >> $@
	@echo '.idea/replstate.xml' >> $@
	@echo '*.iml' >> $@
	@echo '' >> $@
	@echo '/example/example/**' >> $@
	@echo 'artifacts' >> $@
	@echo 'projects/**/pom.xml' >> $@
	@echo '' >> $@
	@echo '# nrepl' >> $@
	@echo '.nrepl-port' >> $@
	@echo '' >> $@
	@echo '# clojure-lsp' >> $@
	@echo '.lsp/.cache' >> $@
	@echo '' >> $@
	@echo '# clj-kondo' >> $@
	@echo '.clj-kondo/.cache' >> $@
	@echo '' >> $@
	@echo '# Calva VS Code Extension' >> $@
	@echo '.calva/output-window/output.calva-repl' >> $@
	@echo '' >> $@
	@echo '# Metaclj tempfiles' >> $@
	@echo '.antqtool.lastupdated' >> $@
	@echo '.enrich-classpath-repl' >> $@

install-gitignore: .gitignore    ## Install a meaningful .gitignore file
	@echo ".gitignore added/exists in the project"

check-tagref:
	tagref

check-cljkondo:
	clj-kondo --lint .

check-zprint:
	zprint -c $(CLOJURE_SOURCES)

check: check-tagref check-cljkondo check-zprint    ## Check that the code is well linted and well formatted
	@echo "All checks passed!"

test-all:
	clojure -M:poly test :all

test-coverage:
	clojure -X:dev:test:clofidence

test:    ## Run Poly tests for the code
	clojure -M:poly test

install-antq:
	@if [ -f .antqtool.lastupdated ] && find .antqtool.lastupdated -mtime +15 -print | grep -q .; then \
		echo "Updating antq tool to the latest version..."; \
		clojure -Ttools install-latest :lib com.github.liquidz/antq :as antq; \
		touch .antqtool.lastupdated; \
	else \
		echo "Skipping antq tool update..."; \
	fi

.antqtool.lastupdated:
	touch .antqtool.lastupdated

upgrade-libs: .antqtool.lastupdated install-antq    ## Install all the deps to their latest versions
	clojure -Tantq outdated :check-clojure-tools true :upgrade true

build: check    ## Build the deployment artifact
	@echo "Run deps-new build commands here!"

deploy: build    ## Deploy the current code to production
	@echo "Run fly.io deployment commands here!"

clean-projects:
	rm -rf projects/*/target/public

clean: clean-projects    ## Delete any existing artifacts
