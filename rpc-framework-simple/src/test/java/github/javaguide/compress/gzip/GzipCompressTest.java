package github.javaguide.compress.gzip;

import github.javaguide.compress.Compress;
import github.javaguide.remoting.dto.RpcRequest;
import github.javaguide.remoting.constants.RpcConstants;
import github.javaguide.serialize.kryo.KryoSerializer;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GzipCompressTest {
    @Test
    void gzipCompressTest() {
        Compress gzipCompress = new GzipCompress();
        RpcRequest rpcRequest = RpcRequest.builder().methodName("hello")
                .parameters(new Object[]{"sayhelooloo", "sayhelooloosayhelooloo"})
                .interfaceName("github.javaguide.HelloService")
                .paramTypes(new Class<?>[]{String.class, String.class})
                .requestId(UUID.randomUUID().toString())
                .group("group1")
                .version("version1")
                .build();
        KryoSerializer kryoSerializer = new KryoSerializer();
        byte[] rpcRequestBytes = kryoSerializer.serialize(rpcRequest);
        byte[] compressRpcRequestBytes = gzipCompress.compress(rpcRequestBytes);
        byte[] decompressRpcRequestBytes = gzipCompress.decompress(compressRpcRequestBytes);
        assertEquals(rpcRequestBytes.length, decompressRpcRequestBytes.length);
    }

    @Test
    void shouldRejectDecompressionBomb() {
        Compress gzipCompress = new GzipCompress();
        byte[] oversizedBody = new byte[RpcConstants.MAX_DECOMPRESSED_BODY_LENGTH + 1];
        byte[] compressed = gzipCompress.compress(oversizedBody);

        assertThrows(IllegalArgumentException.class,
                () -> gzipCompress.decompress(compressed));
    }


}
