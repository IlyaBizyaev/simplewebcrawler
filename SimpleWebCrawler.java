package crawler;

import java.io.*;
import java.net.*;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.*;
import java.util.regex.*;

public class SimpleWebCrawler implements WebCrawler {
    private Downloader downloader;

    public SimpleWebCrawler(Downloader downloader) throws IOException {
        this.downloader = downloader;
    }

    private static List<String> extractTag(String blank, String tagName) {
        List<String> list = new ArrayList<> ();
        Pattern pattern = Pattern.compile("<" + tagName + "[\\s\\S]*?>");
        Matcher matcher = pattern.matcher(blank);
        while (matcher.find()) {
            list.add(blank.substring(matcher.start(), matcher.end()));
        }
        pattern = Pattern.compile("<" + tagName  + "[\\s\\S]*" + "?</" + tagName + ">");
        matcher = pattern.matcher(blank);
        while (matcher.find()) {
            list.add(blank.substring(matcher.start(), matcher.end()));
        }
        return list;
    }

    private static String deleteComments(String page) {
        return page.replaceAll(  "<!--[\\s\\S]*?-->", "");
    }

    private static String extractAttribute(String tag, String attribute) {
        Pattern pattern = Pattern.compile(attribute + "\\s*=\\s*\"([\\s\\S]*?)\"");
        Matcher matcher = pattern.matcher(tag);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String tagInsides(String tag) {
        Pattern pattern = Pattern.compile(">(.*)<");
        Matcher matcher = pattern.matcher(tag);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String generateLocalFilename(String url) {
        return url.replaceAll("://", "_").replaceAll("/", "_");
    }

    @Override
    public Page crawl(String url, int depth) {
        URL currentPage;
        try {
            currentPage = new URL(url);
        } catch (MalformedURLException e) {
            System.out.println(url + " is not a valid URL");
            return null;
        }

        String pageContents = null;
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(downloader.download(url), "utf8"))) {
            StringBuilder text = new StringBuilder();
            String inputLine;
            while ((inputLine = in.readLine()) != null)
                text.append(inputLine);
            pageContents = text.toString();
        } catch (UnsupportedEncodingException e) {
            System.out.println("UTF-8 is not supported on this machine");
        } catch (IOException e) {
            System.out.println("Page read failed: " + e.getMessage());
        }

        if (pageContents == null) {
            return new Page(url, "");
        }

        pageContents = deleteComments(pageContents);

        String title = null;
        for (String tag : extractTag(pageContents, "title")) {
            title = tagInsides(tag);
            if (title != null) {
                break;
            }
        }
        Page result = new Page(url, title);

        if (depth == 0) {
            return result;
        }

        List<String> imgList = extractTag(pageContents, "img");
        Set<String> currentPageImages = new HashSet<>();
        Set<String> currentPageChildren = new HashSet<>();
        for (String tag : imgList) {
            String imgLink = extractAttribute(tag, "src");
            if (imgLink == null) {
                continue;
            }
            URL imgUrl;
            try {
                imgUrl = new URL(currentPage, imgLink);
            } catch (MalformedURLException e) {
                System.out.println(imgLink + " is not a valid URL");
                continue;
            }
            String completeImgUrl = imgUrl.toString();
            if (!currentPageImages.contains(completeImgUrl)) {
                System.out.println(generateLocalFilename(completeImgUrl));
                try {
                    ReadableByteChannel rbc = Channels.newChannel(downloader.download(completeImgUrl));
                    FileOutputStream fos = new FileOutputStream(generateLocalFilename(completeImgUrl));
                    fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
                    Image img = new Image(completeImgUrl, generateLocalFilename(completeImgUrl));
                    result.addImage(img);
                    currentPageImages.add(completeImgUrl);
                } catch (FileNotFoundException e) {
                    System.out.println("Could not create " + generateLocalFilename(completeImgUrl));
                } catch (IOException e) {
                    System.out.println("Could not download " + completeImgUrl);
                }
            }
        }

        List<String> aList = extractTag(pageContents, "a");
        for (String tag : aList) {
            String childLink = extractAttribute(tag, "href");
            if (childLink == null) {
                continue;
            }
            URL childUrl;
            try {
                childUrl = new URL(currentPage, childLink);
            } catch (MalformedURLException e) {
                System.out.println(childLink + " is not a valid URL");
                continue;
            }
            String completeChildUrl = childUrl.toString();
            for (int i = 0; i < completeChildUrl.length(); i++) {
                if (completeChildUrl.charAt(i) == '#') {
                    completeChildUrl = completeChildUrl.substring(0, i);
                    break;
                }
            }
            if (!currentPageChildren.contains(completeChildUrl)) {
                Page childPage = crawl(completeChildUrl, depth - 1);
                if (childPage != null) {
                    result.addLink(childPage);
                }
                currentPageChildren.add(completeChildUrl);
            }
        }

        return result;
    }
}
