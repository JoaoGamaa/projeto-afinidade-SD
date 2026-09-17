package afinidade;

import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class App {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(App.class);
        // Sem modo, inicia a interface e o servidor TCP juntos.
        if (args.length > 0 && (args[0].equals("servidor") || args[0].equals("cliente"))) {
            app.setAdditionalProfiles(args[0]);
            if (args[0].equals("servidor")) app.setWebApplicationType(WebApplicationType.NONE);
            args = Arrays.copyOfRange(args, 1, args.length);
        }
        app.run(args);
    }
}
