import java.util.List;
import java.util.Map;

public class srchTest
{

    public static void main(String[] args){
      //
        System.out.println(999);

        List<Map<String, String>> results = SearchBing.searchBing("台积电");

        for (Map<String, String> r : results) {
            System.out.println(r.get("title") + " -> " + r.get("url"));
        }

    }
}
