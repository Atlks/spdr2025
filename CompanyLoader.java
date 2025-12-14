import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.util.IOUtils;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class CompanyLoader {

    private static final String INPUT_FILE = "C:\\Users\\Administrator\\IdeaProjects\\untitled\\src\\faren.xlsx";

        static {
        IOUtils.setByteArrayMaxOverride(200_000_000); // 200MB
        //
        }

    public static void main(String[] args) {
        List<Company> li=new CompanyLoader().loadCompanies();
        String txt=encodeJson(li);
        writeFil(txt,"cmpns.json");

    }

    private static void writeFil(String txt, String f) {
        try {
            Files.write(Paths.get(f), txt.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            e.printStackTrace();
        }



    }

    private static String encodeJson(List<Company> li) {

        try {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.writeValueAsString(li);
        } catch (Exception e) {
            e.printStackTrace();
            return "[]";
        }

    }


    // 假设 completed 是一个已存在的集合
    private Set<String> completed = new HashSet<>();

    public List<Company> loadCompanies() {
        List<Company> companies = new ArrayList<>();

        try (FileInputStream fis = new FileInputStream(INPUT_FILE);
             Workbook wb = new XSSFWorkbook(fis)) {

            Sheet sheet = wb.getSheetAt(0);

            // 从第二行开始读取（索引从 0 开始，所以是 row 1）
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                Cell nameCell = row.getCell(1);  // Python row[1]
                Cell legalCell = row.getCell(2); // Python row[2]

                if (nameCell != null) {
                    String name = nameCell.toString()
                            .trim()
                            .replace(" ", "")
                            .replace("\u200B", "");

                    String legal = legalCell != null ? legalCell.toString().trim() : "";

                    if (!name.isEmpty() && !completed.contains(name)) {
                        companies.add(new Company(name, legal));
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return companies;
    }

    // 简单的 Company 数据类
    public static class Company {
        public String name;
        public String legal;

        public Company(String name, String legal) {
            this.name = name;
            this.legal = legal;
        }
    }
}

