package com.sedmelluq.discord.lavaplayer.tools.io

import com.sedmelluq.discord.lavaplayer.tools.Units
import org.apache.hc.client5.http.classic.methods.HttpGet
import org.apache.hc.core5.http.ClassicHttpResponse
import org.apache.hc.core5.http.HttpEntity
import org.apache.hc.core5.http.Header
import org.apache.hc.core5.http.message.BasicHeader
import spock.lang.Specification
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketException

class PersistentHttpStreamSpec extends Specification {
    def httpInterface = Mock(HttpInterface)
    def uri = new URI("http://localhost/test")

    def "stream successfully connects and reads content length from header"() {
        given:
        def response = Mock(ClassicHttpResponse)
        def entity = Mock(HttpEntity)
        def contentStream = new ByteArrayInputStream([1, 2, 3] as byte[])
        
        response.getCode() >> 200
        response.getEntity() >> entity
        entity.getContent() >> contentStream
        response.getFirstHeader("Content-Length") >> new BasicHeader("Content-Length", "3")
        
        def stream = new PersistentHttpStream(httpInterface, uri, null)

        when:
        int firstByte = stream.read()

        then:
        1 * httpInterface.execute(_ as HttpGet) >> response
        firstByte == 1
        stream.contentLength == 3
        stream.position == 1
    }

    def "stream throws IOException on non-success status code"() {
        given:
        def response = Mock(ClassicHttpResponse)
        response.getCode() >> 404
        
        def stream = new PersistentHttpStream(httpInterface, uri, null)

        when:
        stream.read()

        then:
        1 * httpInterface.execute(_ as HttpGet) >> response
        thrown(IOException)
    }

    def "stream retries on 500 server error and then succeeds"() {
        given:
        def failResponse = Mock(ClassicHttpResponse)
        def successResponse = Mock(ClassicHttpResponse)
        def entity = Mock(HttpEntity)
        def contentStream = new ByteArrayInputStream([42] as byte[])

        failResponse.getCode() >> 500
        successResponse.getCode() >> 200
        successResponse.getEntity() >> entity
        entity.getContent() >> contentStream

        def stream = new PersistentHttpStream(httpInterface, uri, null)

        when:
        int value = stream.read()

        then:
        1 * httpInterface.execute(_ as HttpGet) >> failResponse
        1 * httpInterface.execute(_ as HttpGet) >> successResponse
        1 * failResponse.close()
        value == 42
    }

    def "read with buffer and skip modify position correctly"() {
        given:
        def response = Mock(ClassicHttpResponse)
        def entity = Mock(HttpEntity)
        def contentStream = new ByteArrayInputStream([10, 20, 30, 40, 50] as byte[])
        
        response.getCode() >> 200
        response.getEntity() >> entity
        entity.getContent() >> contentStream
        
        def stream = new PersistentHttpStream(httpInterface, uri, 5L)

        when:
        byte[] buffer = new byte[2]
        int bytesRead = stream.read(buffer, 0, 2)
        long skipped = stream.skip(2)
        int nextByte = stream.read()

        then:
        1 * httpInterface.execute(_ as HttpGet) >> response
        bytesRead == 2
        buffer == [10, 20] as byte[]
        skipped == 2
        nextByte == 50
        stream.position == 5
    }

    def "reconnects on retriable network exception"() {
        given:
        def response1 = Mock(ClassicHttpResponse)
        def entity1 = Mock(HttpEntity)
        def contentStream1 = Mock(InputStream)
        
        response1.getCode() >> 200
        response1.getEntity() >> entity1
        entity1.getContent() >> contentStream1
        
        // Stub all variations of read to throw SocketException
        contentStream1.read() >> { throw new SocketException("Connection reset") }
        contentStream1.read(_ as byte[]) >> { throw new SocketException("Connection reset") }
        contentStream1.read(_ as byte[], _ as int, _ as int) >> { throw new SocketException("Connection reset") }

        def response2 = Mock(ClassicHttpResponse)
        def entity2 = Mock(HttpEntity)
        def contentStream2 = new ByteArrayInputStream([99] as byte[])

        response2.getCode() >> 200
        response2.getEntity() >> entity2
        entity2.getContent() >> contentStream2

        def stream = new PersistentHttpStream(httpInterface, uri, 100L)

        when:
        int value = stream.read()

        then:
        // First connection
        1 * httpInterface.execute(_ as HttpGet) >> response1
        // Reconnect attempt
        1 * httpInterface.execute(_ as HttpGet) >> response2
        1 * response1.close()
        value == 99
        stream.position == 1
    }

    def "available returns delegate available count"() {
        given:
        def response = Mock(ClassicHttpResponse)
        def entity = Mock(HttpEntity)
        def contentStream = new ByteArrayInputStream([1, 2, 3] as byte[])
        
        response.getCode() >> 200
        response.getEntity() >> entity
        entity.getContent() >> contentStream
        
        def stream = new PersistentHttpStream(httpInterface, uri, 3L)

        when:
        int avail = stream.available()

        then:
        1 * httpInterface.execute(_ as HttpGet) >> response
        avail == 3
    }

    def "seekHard closes connection and updates position"() {
        given:
        def response = Mock(ClassicHttpResponse)
        def entity = Mock(HttpEntity)
        def contentStream = new ByteArrayInputStream([1, 2, 3] as byte[])
        
        response.getCode() >> 200
        response.getEntity() >> entity
        entity.getContent() >> contentStream
        httpInterface.execute(_ as HttpGet) >> response
        
        def stream = new PersistentHttpStream(httpInterface, uri, 10L)
        stream.read()
        stream.read() // current position is now 2

        when:
        stream.seek(1) // seeking backwards forces seekHard

        then:
        1 * response.close()
        stream.position == 1
        stream.canSeekHard() == true
    }

    def "mark is not supported"() {
        given:
        def stream = new PersistentHttpStream(httpInterface, uri, 10L)

        expect:
        !stream.markSupported()
        
        when:
        stream.reset()
        
        then:
        thrown(IOException)
    }

    def "getTrackInfoProviders parses icy headers"() {
        given:
        def response = Mock(ClassicHttpResponse)
        def entity = Mock(HttpEntity)
        
        response.getCode() >> 200
        response.getEntity() >> entity
        entity.getContent() >> new ByteArrayInputStream([] as byte[])
        response.getFirstHeader("icy-description") >> new BasicHeader("icy-description", "Test Description")
        response.getFirstHeader("icy-name") >> new BasicHeader("icy-name", "Test Author")
        httpInterface.execute(_ as HttpGet) >> response

        def stream = new PersistentHttpStream(httpInterface, uri, 10L)
        stream.connect(false)

        when:
        def providers = stream.getTrackInfoProviders()

        then:
        providers.size() == 1
        providers[0].title == "Test Description"
        providers[0].author == "Test Author"
    }

    def "releaseConnection closes stream but keeps response alive for reuse"() {
        given:
        def response = Mock(ClassicHttpResponse)
        def entity = Mock(HttpEntity)
        def contentStream = Mock(InputStream)
        
        response.getCode() >> 200
        response.getEntity() >> entity
        entity.getContent() >> contentStream
        httpInterface.execute(_ as HttpGet) >> response
        
        def stream = new PersistentHttpStream(httpInterface, uri, 10L)
        stream.connect(false)

        when:
        stream.releaseConnection()

        then:
        1 * contentStream.close()
        0 * response.close()
        stream.currentContent == null
        stream.getCurrentResponse() == null
    }
}
