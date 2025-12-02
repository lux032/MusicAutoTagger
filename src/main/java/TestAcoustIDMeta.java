import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.entity.UrlEncodedFormEntity;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.message.BasicNameValuePair;

import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * 测试不同的 meta 参数组合
 */
public class TestAcoustIDMeta {
    public static void main(String[] args) throws Exception {
        // 读取配置
        Properties props = new Properties();
        props.load(new FileInputStream("config.properties"));
        String apiKey = props.getProperty("acoustid.api.key");
        
        // 测试指纹(一个已知的示例)
        String testFingerprint = "AQADtNQSJUqUHEcWHceRI4ceHEcOXcd1HOFxPDnyI0-OTEeu4kf-ozhyIz9yHLnx40GOHI-SI7mOXDny4ziOHDmu40hy5DhyHPkQHzmO6ziO40iO5DhyPDkQHTnyHL-O_Ej-I0-OI0eO48hxHfmRI8-PIz-SH_mR4ziO4ziS40h-5EeO5DiO48eRH0dyHMmRHzly5MiPXMePPEeO4ziO40hx5Ehy5MiR48hxHDny48hxPEd-PEeOHEd-5MhxXMePHDny48iPHDly5MhxJEdy5EeO40iO48iPIzmSI_mRI0eeI0eO4ziOHMdx5EeOPMdxHMeR48iPI8dx5MhxHDnyI8-R4ziO_EiO48iRH_mRI0d-5DiO48iPHMmP5DiO48hxHMdxJMdxHEeOI0eOI0euI8eRH8mRH_mRI0d-HMdx5MiPPDly5Eeeh";
        int testDuration = 180;
        
        CloseableHttpClient httpClient = HttpClients.createDefault();
        ObjectMapper objectMapper = new ObjectMapper();
        
        // 测试不同的 meta 参数
        String[] metaOptions = {
            "recordings+releasegroups+compress",  // 当前使用的
            "recordings+releasegroups",           // 不使用压缩
            "recordings",                          // 只要 recordings
            "recordingids"                         // 只要 recording IDs
        };
        
        for (String meta : metaOptions) {
            System.out.println("\n========================================");
            System.out.println("测试 meta 参数: " + meta);
            System.out.println("========================================");
            
            List<NameValuePair> params = new ArrayList<>();
            params.add(new BasicNameValuePair("client", apiKey));
            params.add(new BasicNameValuePair("duration", String.valueOf(testDuration)));
            params.add(new BasicNameValuePair("fingerprint", testFingerprint));
            params.add(new BasicNameValuePair("meta", meta));
            
            HttpPost httpPost = new HttpPost("https://api.acoustid.org/v2/lookup");
            httpPost.setEntity(new UrlEncodedFormEntity(params, StandardCharsets.UTF_8));
            httpPost.setHeader("User-Agent", "MusicTagging/1.0");
            httpPost.setHeader("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            
            try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
                String responseBody = EntityUtils.toString(response.getEntity());
                System.out.println("响应状态码: " + response.getCode());
                System.out.println("响应内容: " + responseBody);
                
                // 解析并检查 recordings 字段
                JsonNode root = objectMapper.readTree(responseBody);
                JsonNode results = root.path("results");
                if (results.isArray() && results.size() > 0) {
                    JsonNode firstResult = results.get(0);
                    JsonNode recordings = firstResult.path("recordings");
                    System.out.println("是否有 recordings 字段: " + !recordings.isMissingNode());
                    System.out.println("recordings 是否为数组: " + recordings.isArray());
                    if (recordings.isArray()) {
                        System.out.println("recordings 数量: " + recordings.size());
                    }
                }
            }
            
            // 等待一下,避免触发速率限制
            Thread.sleep(1000);
        }
        
        httpClient.close();
    }
}