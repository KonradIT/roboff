package com.roboff.lan.discovery

 /** A robot advertised on the local network. Authentication is never part of discovery.  */
 class DiscoveredDevice (id:kotlin.String?, name:kotlin.String?, host:kotlin.String?, port:Int, 
source:kotlin.String?, protocol:kotlin.String?) {
     val id:kotlin.String?
     val name:kotlin.String?
     val host:kotlin.String?
     val port:Int
     val source:kotlin.String?
     val protocol:kotlin.String?
    init  {
        this.id = if (id == null)"" else id
        this.name = if (name == null)"Roborock" else name
        this.host = host
        this.port = port
        this.source = source
        this.protocol = if (protocol == null)"" else protocol
    }
}
