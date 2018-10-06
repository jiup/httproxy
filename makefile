SDIR = ./src/
DDIR = ./bin/
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
	cp config $(DDIR)
	@echo 'java server.Main $$1' > $(DDIR)ProxyServer
	chmod +x $(DDIR)ProxyServer
	@echo 'build complete!'

classes: $(CLASSES:.java=.class)

clean:
	$(RM) -r $(DDIR)server/*.class \
	$(DDIR)util/*.class \
	$(DDIR)config \
	$(DDIR)ProxyServer
	@echo 'done!'

dpclean:
	$(RM) -r $(DDIR)
	@echo 'done!'