import dev.avillagran.t3.T3Client;
import org.json.JSONObject;
import java.nio.file.*;
/** Offline export with pinned AAR, host JNI, org.json and Kotlin stdlib. */
public class Export {
 public static void main(String[] paths) throws Exception {
  try (T3Client client = new T3Client()) {
   System.out.println(client.execute(new JSONObject().put("op", "capabilities")));
   for (String path : paths) {
    JSONObject projection = new JSONObject(Files.readString(Path.of(path)));
    JSONObject view = (JSONObject) client.domain("timeline.view", new JSONObject().put("projection", projection));
    Files.writeString(Path.of(path.replace(".projection.json", ".view.json")), view.toString(2) + "\n");
   }
  }
 }
}
