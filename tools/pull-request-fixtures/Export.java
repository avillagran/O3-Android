import dev.avillagran.t3.T3Client;
import org.json.*;
import java.nio.file.*;
/** Offline fixture projection with the pinned AAR; no network. */
public class Export {
 static T3Client client;
 static void markdown(JSONObject item) { item.put("blocks", client.domain("markdown.blocks", new JSONObject().put("markdown", item.optString("body")))); }
 public static void main(String[] paths) throws Exception {
  try (T3Client handle = new T3Client()) {
   client = handle;
   for (String path : paths) {
    JSONObject data = new JSONObject(Files.readString(Path.of(path)));
    if (path.endsWith("list.json")) for (Object value : data.getJSONArray("entries")) {
     JSONObject entry = (JSONObject)value;
     JSONObject link = new JSONObject(entry.toString()).put("snapshot", new JSONObject(entry.toString()));
     entry.put("badge", client.domain("pullRequests.sidebarBadge", new JSONObject().put("thread", new JSONObject().put("projectId", entry.getString("projectId")).put("pullRequests", new JSONArray().put(link)))));
    }
    if (path.endsWith("detail.json")) markdown(data);
    if (path.endsWith("activity.json")) {
     for (Object comment : data.getJSONArray("comments")) markdown((JSONObject)comment);
     for (Object thread : data.getJSONArray("reviewThreads")) for (Object comment : ((JSONObject)thread).getJSONArray("comments")) markdown((JSONObject)comment);
    }
    Files.writeString(Path.of(path.replace(".json", ".view.json")), data.toString(2)+"\n");
   }
  }
 }
}
