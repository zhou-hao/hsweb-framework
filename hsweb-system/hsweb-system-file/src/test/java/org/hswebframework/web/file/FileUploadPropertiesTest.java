package org.hswebframework.web.file;

import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.junit.Test;
import org.springframework.http.MediaType;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashSet;

import static org.junit.Assert.*;

public class FileUploadPropertiesTest {


    @Test
    public void testNoSet() {
        FileUploadProperties uploadProperties = new FileUploadProperties();
        assertFalse(uploadProperties.denied("test.xls", MediaType.ALL));

        assertFalse(uploadProperties.denied("test.exe", MediaType.ALL));
    }

    @Test
    public void testDenyWithAllow() {
        FileUploadProperties uploadProperties = new FileUploadProperties();
        uploadProperties.setAllowFiles(new HashSet<>(Arrays.asList("xls", "json")));

        assertFalse(uploadProperties.denied("test.xls", MediaType.ALL));
        assertFalse(uploadProperties.denied("test.XLS", MediaType.ALL));

        assertTrue(uploadProperties.denied("test.exe", MediaType.ALL));
    }

    @Test
    public void testDenyWithAllowMediaType() {
        FileUploadProperties uploadProperties = new FileUploadProperties();
        uploadProperties.setAllowMediaType(new HashSet<>(Arrays.asList("application/xls", "application/json")));

        assertFalse(uploadProperties.denied("test.json", MediaType.APPLICATION_JSON));

        assertTrue(uploadProperties.denied("test.exe", MediaType.ALL));
    }


    @Test
    public void testDenyWithDenyMediaType() {
        FileUploadProperties uploadProperties = new FileUploadProperties();
        uploadProperties.setDenyMediaType(new HashSet<>(Arrays.asList("application/json")));

        assertFalse(uploadProperties.denied("test.xls", MediaType.ALL));

        assertTrue(uploadProperties.denied("test.exe", MediaType.APPLICATION_JSON));

    }

    @Test
    public void testDenyWithDeny() {
        FileUploadProperties uploadProperties = new FileUploadProperties();
        uploadProperties.setDenyFiles(new HashSet<>(Arrays.asList("exe")));

        assertFalse(uploadProperties.denied("test.xls", MediaType.ALL));

        assertTrue(uploadProperties.denied("test.exe", MediaType.ALL));

    }

    @Test
    // https://github.com/hs-web/hsweb-framework/issues/362
    public void testDenyWithTrailingSeparator() {
        FileUploadProperties uploadProperties = new FileUploadProperties();
        uploadProperties.setDenyFiles(new HashSet<>(Arrays.asList("jsp")));

        assertTrue(uploadProperties.denied("x.jsp/", MediaType.ALL));
        assertTrue(uploadProperties.denied("x.jsp\\", MediaType.ALL));

        FileUploadProperties.StaticFileInfo fileInfo = uploadProperties.createStaticSavePath("x.jsp/");
        assertTrue(fileInfo.getLocation().endsWith(".jsp"));
    }


    @Test
    // https://github.com/hs-web/hsweb-framework/issues/344
    public void testIllegalFileName() {
        FileUploadProperties uploadProperties = new FileUploadProperties();
        uploadProperties.setUseOriginalFileName(true);

        // 基本的路径遍历攻击
        FileUploadProperties.StaticFileInfo fileInfo = uploadProperties
            .createStaticSavePath("../../../../pom.xml");
        assertFalse(fileInfo.getSavePath().contains("../"));
        assertFalse(fileInfo.getRelativeLocation().contains("../"));
        assertFalse(fileInfo.getLocation().contains("../"));

        // Windows风格的路径遍历攻击
        fileInfo = uploadProperties.createStaticSavePath("..\\..\\..\\..\\pom.xml");
        assertFalse(fileInfo.getSavePath().contains("..\\"));
        assertFalse(fileInfo.getRelativeLocation().contains("..\\"));
        assertFalse(fileInfo.getLocation().contains("..\\"));

        // URL编码的路径遍历
        fileInfo = uploadProperties.createStaticSavePath("..%2F..%2F..%2F..%2Fpom.xml");
        assertFalse(fileInfo.getSavePath().contains("../"));
        assertFalse(fileInfo.getSavePath().contains("..%2F"));
        assertFalse(fileInfo.getRelativeLocation().contains("../"));
        assertFalse(fileInfo.getLocation().contains("../"));

        // 双重URL编码
        fileInfo = uploadProperties.createStaticSavePath("..%252F..%252F..%252Fpom.xml");
        assertFalse(fileInfo.getSavePath().contains("../"));
        assertFalse(fileInfo.getSavePath().contains("..%2F"));
        assertFalse(fileInfo.getSavePath().contains("..%252F"));

        // Unicode编码的路径遍历
        fileInfo = uploadProperties.createStaticSavePath("..%c0%af..%c0%afpom.xml");
        assertFalse(fileInfo.getSavePath().contains("../"));
        assertFalse(fileInfo.getRelativeLocation().contains("../"));

        // 绝对路径攻击 - Linux
        fileInfo = uploadProperties.createStaticSavePath("/etc/passwd");
        assertFalse(fileInfo.getSavePath().startsWith("/etc/"));
        assertFalse(fileInfo.getLocation().contains("/etc/passwd"));

        // 绝对路径攻击 - Windows
        fileInfo = uploadProperties.createStaticSavePath("C:\\Windows\\System32\\config\\sam");
        assertFalse(fileInfo.getSavePath().contains("C:\\"));
        assertFalse(fileInfo.getSavePath().contains("System32"));

        // 混合斜杠
        fileInfo = uploadProperties.createStaticSavePath("..\\../..\\../pom.xml");
        assertFalse(fileInfo.getSavePath().contains("../"));
        assertFalse(fileInfo.getSavePath().contains("..\\"));

        // 过度的路径遍历
        fileInfo = uploadProperties.createStaticSavePath("../../../../../../../../../../../../etc/passwd");
        assertFalse(fileInfo.getSavePath().contains("../"));
        assertFalse(fileInfo.getLocation().contains("/etc/"));


//        // 带有空字节注入
         assertThrows(AccessDenyException.class,
                      ()->{
                          uploadProperties.createStaticSavePath("../../pom.xml\0.jpg");
                      });

        // 点和斜杠的各种组合
        fileInfo = uploadProperties.createStaticSavePath("....//....//pom.xml");
        assertFalse(fileInfo.getSavePath().contains(".."));
        assertFalse(fileInfo.getSavePath().contains("//"));

        // 反斜杠编码
        fileInfo = uploadProperties.createStaticSavePath("..%5c..%5cpom.xml");
        assertFalse(fileInfo.getSavePath().contains("..\\"));
        assertFalse(fileInfo.getSavePath().contains("..%5c"));
    }


}
