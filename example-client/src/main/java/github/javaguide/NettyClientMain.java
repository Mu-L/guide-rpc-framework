package github.javaguide;

import github.javaguide.annotation.RpcScan;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * @author shuang.kou
 * @createTime 2020年05月10日 07:25:00
 */
@RpcScan(basePackage = {"github.javaguide"})
public class NettyClientMain {
    public static void main(String[] args) throws InterruptedException {
        try (AnnotationConfigApplicationContext applicationContext =
                     new AnnotationConfigApplicationContext(NettyClientMain.class)) {
            HelloController helloController = applicationContext.getBean(HelloController.class);
            helloController.test();
        }
    }
}
