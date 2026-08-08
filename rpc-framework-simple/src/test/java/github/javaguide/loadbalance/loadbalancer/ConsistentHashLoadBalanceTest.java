package github.javaguide.loadbalance.loadbalancer;

import github.javaguide.loadbalance.LoadBalance;
import github.javaguide.remoting.dto.RpcRequest;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsistentHashLoadBalanceTest {

    @Test
    void shouldRouteEqualParametersToSameProvider() {
        LoadBalance loadBalance = new ConsistentHashLoadBalanceNew();
        List<String> addresses = Arrays.asList("127.0.0.1:9001", "127.0.0.1:9002");
        RpcRequest firstRequest = request("request-1", "same-argument");
        RpcRequest secondRequest = request("request-2", "same-argument");

        String firstAddress = loadBalance.selectServiceAddress(addresses, firstRequest);
        String secondAddress = loadBalance.selectServiceAddress(addresses, secondRequest);

        assertEquals(firstAddress, secondAddress);
    }

    @Test
    void shouldKeepIndependentHashRingsForDifferentServices() {
        LoadBalance loadBalance = new ConsistentHashLoadBalanceNew();
        List<String> firstServiceAddresses =
                Arrays.asList("127.0.0.1:9001", "127.0.0.1:9002");
        List<String> secondServiceAddresses =
                Arrays.asList("127.0.0.1:9011", "127.0.0.1:9012");

        String firstSelection = loadBalance.selectServiceAddress(
                firstServiceAddresses, requestForService("service.One", "request-1"));
        String secondSelection = loadBalance.selectServiceAddress(
                secondServiceAddresses, requestForService("service.Two", "request-2"));

        assertTrue(firstServiceAddresses.contains(firstSelection));
        assertTrue(secondServiceAddresses.contains(secondSelection));
    }

    @Test
    void shouldRemoveDepartedProvidersAndAddNewProvidersWhenRingChanges() {
        ConsistentHashLoadBalanceNew.ConsistentHashingLoadBalancer selector =
                new ConsistentHashLoadBalanceNew.ConsistentHashingLoadBalancer(
                        Arrays.asList("127.0.0.1:9001", "127.0.0.1:9002"),
                        160,
                        new ConsistentHashLoadBalanceNew.ConsistentHashingLoadBalancer
                                .MD5HashFunction());
        List<String> updatedAddresses =
                Arrays.asList("127.0.0.1:9002", "127.0.0.1:9003");

        selector.reBuild(updatedAddresses);

        assertEquals(new HashSet<>(updatedAddresses), new HashSet<>(selector.getAllNodes()));
        Set<String> selectedNodes = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            selectedNodes.add(selector.selectNode("request-" + i));
        }
        assertTrue(updatedAddresses.containsAll(selectedNodes));
    }

    private static RpcRequest request(String requestId, Object parameter) {
        return RpcRequest.builder()
                .requestId(requestId)
                .interfaceName("service.Demo")
                .methodName("call")
                .parameters(new Object[]{parameter})
                .paramTypes(new Class<?>[]{String.class})
                .group("")
                .version("")
                .build();
    }

    private static RpcRequest requestForService(String serviceName, String requestId) {
        return RpcRequest.builder()
                .requestId(requestId)
                .interfaceName(serviceName)
                .methodName("call")
                .parameters(new Object[]{"same-argument"})
                .paramTypes(new Class<?>[]{String.class})
                .group("")
                .version("")
                .build();
    }
}
