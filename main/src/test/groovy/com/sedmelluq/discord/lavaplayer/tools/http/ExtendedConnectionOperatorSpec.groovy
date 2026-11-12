package com.sedmelluq.discord.lavaplayer.tools.http

import org.apache.hc.client5.http.DnsResolver
import org.apache.hc.client5.http.SchemePortResolver
import org.apache.hc.client5.http.io.ManagedHttpClientConnection
import org.apache.hc.client5.http.socket.ConnectionSocketFactory
import org.apache.hc.core5.http.HttpHost
import org.apache.hc.core5.http.config.Lookup
import org.apache.hc.core5.http.io.SocketConfig
import org.apache.hc.core5.http.protocol.BasicHttpContext
import org.apache.hc.core5.util.TimeValue
import spock.lang.Specification

import java.net.InetAddress
import java.net.Socket
import java.net.SocketException

class ExtendedConnectionOperatorSpec extends Specification {

    def "closes unbound socket when configureSocket throws an exception"() {
        given:
        def socket = Mock(Socket)
        def socketFactory = Mock(ConnectionSocketFactory)
        def registry = Mock(Lookup)
        def dnsResolver = Mock(DnsResolver)
        def connection = Mock(ManagedHttpClientConnection)
        def host = new HttpHost("http", "example.com", 80)
        def context = new BasicHttpContext()

        registry.lookup("http") >> socketFactory
        dnsResolver.resolve("example.com") >> ([InetAddress.getByAddress([127, 0, 0, 1] as byte[])] as InetAddress[])
        socketFactory.createSocket(context) >> socket
        socket.setSoTimeout(_ as int) >> { throw new SocketException("Failed to configure socket") }

        def operator = new ExtendedConnectionOperator(registry, null as SchemePortResolver, dnsResolver)

        when:
        operator.connect(connection, host, null, TimeValue.ofSeconds(1), SocketConfig.DEFAULT, context)

        then:
        1 * socket.close()
        0 * connection.bind(_)
        thrown(SocketException)
    }
}
