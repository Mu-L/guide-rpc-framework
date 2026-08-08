package github.javaguide.serialize;

import github.javaguide.remoting.dto.RpcRequest;
import github.javaguide.remoting.dto.RpcResponse;
import github.javaguide.serialize.hessian.HessianSerializer;
import github.javaguide.serialize.kryo.KryoSerializer;
import github.javaguide.serialize.protostuff.ProtostuffSerializer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SerializerCompatibilityTest {

    static Stream<Serializer> serializers() {
        return Stream.of(
                new HessianSerializer(),
                new KryoSerializer(),
                new ProtostuffSerializer());
    }

    @ParameterizedTest
    @MethodSource("serializers")
    void shouldRoundTripRpcRequest(Serializer serializer) {
        RpcRequest request = RpcRequest.builder()
                .requestId("serializer-request")
                .interfaceName("example.EchoService")
                .methodName("echo")
                .parameters(new Object[]{"hello", 7})
                .paramTypes(new Class<?>[]{String.class, Integer.class})
                .group("group")
                .version("v1")
                .build();

        RpcRequest decoded = serializer.deserialize(
                serializer.serialize(request), RpcRequest.class);

        assertEquals(request.getRequestId(), decoded.getRequestId());
        assertEquals(request.getInterfaceName(), decoded.getInterfaceName());
        assertEquals(request.getMethodName(), decoded.getMethodName());
        assertArrayEquals(request.getParameters(), decoded.getParameters());
        assertArrayEquals(request.getParamTypes(), decoded.getParamTypes());
        assertEquals(request.getGroup(), decoded.getGroup());
        assertEquals(request.getVersion(), decoded.getVersion());
    }

    @ParameterizedTest
    @MethodSource("serializers")
    void shouldRoundTripRpcResponse(Serializer serializer) {
        RpcResponse<Object> response = RpcResponse.success("hello", "serializer-response");

        RpcResponse<?> decoded = serializer.deserialize(
                serializer.serialize(response), RpcResponse.class);

        assertEquals(response.getRequestId(), decoded.getRequestId());
        assertEquals(response.getCode(), decoded.getCode());
        assertEquals(response.getMessage(), decoded.getMessage());
        assertEquals(response.getData(), decoded.getData());
    }
}
