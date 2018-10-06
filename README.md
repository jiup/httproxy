## CSC457 - Web Proxy

#### Overview

* This program was written based on the java selector NIO.

* It **supports** persistent HTTP connection by check both HTTP requests and responses and scans their headers.

	```java
	futureClose.add(socketChannel);
	```
	
	If a "connection: close" header was signaled, the proxy will hold it till the end of traffic, then cut off both local and remote side connections to make sure no more requests to be sent through this link.
	
	Moreover, when HTTP/1.0 with header "connection: <non-close>" detected, the program still handle it as 
	persistent connection.

#### Requirements

- JDK 1.8+
	
#### Structure of directory

```
p2_jzh149
├── README.md *
├── config
├── makefile
├── p2_jzh149.iml
└── src
    ├── server
    │   ├── Main.java
    │   └── ProxyServer.java
    └── util
        └── Config.java
```

#### How to use

- compile and build: `./path/to/p2_jzh149/make`
- run: `./path/to/p2_jzh149/bin/ProxyServer <config_file>`