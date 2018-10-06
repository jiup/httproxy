SDIR = ./src/
DDIR = ./
JC = javac

.SUFFIXES: .java .class
.java.class:
	$(JC) -sourcepath $(SDIR) -d $(DDIR) $*.java

CLASSES = \
	$(SDIR)util/Config.java \
	$(SDIR)server/ProxyServer.java \
	$(SDIR)server/Main.java

default: install

init:
	mkdir -p $(DDIR)

install: init classes
	@echo 'java server.Main $$1' > $(DDIR)ProxyServer
	chmod +x $(DDIR)ProxyServer
	@echo 'build complete!'

classes: $(CLASSES:.java=.class)

clean:
	$(RM) -r $(DDIR)util/ \
	$(DDIR)server/ \
	$(DDIR)ProxyServer
	@echo 'done!'