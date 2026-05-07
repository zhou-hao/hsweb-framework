package org.hswebframework.web.file;

import lombok.Getter;
import lombok.Setter;
import org.apache.commons.collections4.CollectionUtils;
import org.hswebframework.utils.time.DateFormatter;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.id.IDGenerator;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.MediaType;

import java.io.File;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.text.Normalizer;
import java.util.Date;
import java.util.Locale;
import java.util.Set;

@Getter
@Setter
@ConfigurationProperties(prefix = "hsweb.file.upload")
public class FileUploadProperties {

    private String staticFilePath = "./static";

    private String staticLocation = "/static";

    //是否使用原始文件名进行存储
    private boolean useOriginalFileName = false;

    private Set<String> allowFiles;

    private Set<String> denyFiles;

    private Set<String> allowMediaType;

    private Set<String> denyMediaType;

    private Set<PosixFilePermission> permissions;

    public void applyFilePermission(File file) {

        if (CollectionUtils.isEmpty(permissions)) {
            return;
        }
        try {
            Path path = Paths.get(file.toURI());
            PosixFileAttributeView view = Files.getFileAttributeView(path, PosixFileAttributeView.class);
            view.setPermissions(permissions);
        } catch (Throwable ignore) {

        }


    }

    public boolean denied(String name, MediaType mediaType) {
        String suffix = (name.contains(".") ? name.substring(name.lastIndexOf(".") + 1) : "").toLowerCase(Locale.ROOT);
        boolean defaultDeny = false;
        if (CollectionUtils.isNotEmpty(denyFiles)) {
            if (denyFiles.contains(suffix)) {
                return true;
            }
            defaultDeny = false;
        }

        if (CollectionUtils.isNotEmpty(allowFiles)) {
            if (allowFiles.contains(suffix)) {
                return false;
            }
            defaultDeny = true;
        }

        if (CollectionUtils.isNotEmpty(denyMediaType)) {
            if (denyMediaType.contains(mediaType.toString())) {
                return true;
            }
            defaultDeny = false;
        }

        if (CollectionUtils.isNotEmpty(allowMediaType)) {
            if (allowMediaType.contains(mediaType.toString())) {
                return false;
            }
            defaultDeny = true;
        }

        return defaultDeny;
    }

    public static String resolveExtension(String name) {
        int lastIndex = name.lastIndexOf(".");
        if (lastIndex < 0) {
            return "";
        }
        return name.substring(lastIndex).toLowerCase(Locale.ROOT);
    }

    public StaticFileInfo createStaticSavePath(String name) {
        String fileName = IDGenerator.SNOW_FLAKE_STRING.generate();
        String filePath = DateFormatter.toString(new Date(), "yyyyMMdd");
        try {
            name = Paths
                .get(Normalizer
                         .normalize(name, Normalizer.Form.NFKC)
                         .replace("\\", "/"))
                .toFile()
                .getName();
        } catch (InvalidPathException e) {
            throw new AccessDenyException.NoStackTrace();
        }

        //文件后缀
        String suffix = resolveExtension(name);

        StaticFileInfo info = new StaticFileInfo();

        // 仅支持 字母数字组成的文件名
        if (useOriginalFileName && name.matches("^[a-zA-Z0-9._-]+$")) {
            filePath = filePath + "/" + fileName;
            fileName = name;
        } else {
            fileName = fileName + suffix;
        }
        String absPath = staticFilePath.concat("/").concat(filePath);

        boolean ignore = new File(absPath).mkdirs();

        Path fullPath = Paths.get(absPath, fileName);
        info.savePath = fullPath.normalize().toString();

        info.relativeLocation = filePath + "/" + fileName;
        info.location = staticLocation + "/" + filePath + "/" + fileName;
        return info;
    }

    @Getter
    @Setter
    public static class StaticFileInfo {

        private String savePath;

        private String relativeLocation;
        private String location;
    }
}
