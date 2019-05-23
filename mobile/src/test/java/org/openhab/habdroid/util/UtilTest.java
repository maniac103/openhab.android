package org.openhab.habdroid.util;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.openhab.habdroid.model.Sitemap;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.StringReader;
import java.security.cert.CertPathValidatorException;
import java.util.List;

import javax.net.ssl.SSLException;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

public class UtilTest {
    @Test
    public void normalizeUrl() {
        assertEquals("http://localhost/", Util.INSTANCE.normalizeUrl("http://localhost/"));
        assertEquals("http://localhost/", Util.INSTANCE.normalizeUrl("http://localhost"));
        assertEquals("http://127.0.0.1/", Util.INSTANCE.normalizeUrl("http://127.0.0.1/"));
        assertEquals("http://127.0.0.1/", Util.INSTANCE.normalizeUrl("http://127.0.0.1"));

        assertEquals("https://127.0.0.1/", Util.INSTANCE.normalizeUrl("https://127.0.0.1/"));
        assertEquals("https://127.0.0.1/", Util.INSTANCE.normalizeUrl("https://127.0.0.1"));

        assertEquals("https://127.0.0.1/abc/", Util.INSTANCE.normalizeUrl("https://127.0.0.1/abc/"));
        assertEquals("https://127.0.0.1/abc/", Util.INSTANCE.normalizeUrl("https://127.0.0.1/abc"));

        assertEquals("https://127.0.0.1:81/abc/", Util.INSTANCE.normalizeUrl("https://127.0.0.1:81/abc"));
    }

    @Test
    public void parseOH1SitemapList() throws Exception {
        List<Sitemap> sitemapList = Util.INSTANCE.parseSitemapList(getSitemapOH1Document());
        assertFalse(sitemapList.isEmpty());

        assertEquals("i AM DEfault", sitemapList.get(0).label());
        assertEquals("Heating", sitemapList.get(1).label());
        assertEquals("Lighting", sitemapList.get(2).label());
        assertEquals("Heatpump", sitemapList.get(3).label());
        assertEquals("Schedule", sitemapList.get(4).label());
        assertEquals("outside", sitemapList.get(5).label());
        assertEquals("Garden", sitemapList.get(6).label());
        assertEquals("Scenes", sitemapList.get(7).label());

        assertEquals(8, sitemapList.size());
    }

    @Test
    public void parseOH2SitemapListWithId1() throws Exception {
        List<Sitemap> sitemapList = Util.INSTANCE.parseSitemapList(createJsonArray(1));
        assertFalse(sitemapList.isEmpty());

        assertEquals("Main Menu", sitemapList.get(0).label());
        assertEquals(1, sitemapList.size());
    }

    @Test
    public void parseOH2SitemapListWithId2() throws Exception {
        List<Sitemap> sitemapList  = Util.INSTANCE.parseSitemapList(createJsonArray(2));
        assertFalse(sitemapList.isEmpty());

        assertEquals("Main Menu", sitemapList.get(0).label());
        assertEquals("HOME", sitemapList.get(1).label());
        assertEquals("test", sitemapList.get(2).label());
        assertEquals(3, sitemapList.size());
    }

    @Test
    public void parseOH2SitemapListWithId3() throws Exception {
        List<Sitemap> sitemapList = Util.INSTANCE.parseSitemapList(createJsonArray(3));
        assertFalse(sitemapList.isEmpty());

        assertEquals("Home", sitemapList.get(0).label());
        assertEquals(1, sitemapList.size());
    }

    @Test
    public void testSortSitemapList()
            throws IOException, SAXException, ParserConfigurationException {
        List<Sitemap> sitemapList = Util.INSTANCE.parseSitemapList(getSitemapOH1Document());

        Util.INSTANCE.sortSitemapList(sitemapList, "");
        // Should be sorted
        assertEquals("Garden", sitemapList.get(0).label());
        assertEquals("Heating", sitemapList.get(1).label());
        assertEquals("Heatpump", sitemapList.get(2).label());
        assertEquals("i AM DEfault", sitemapList.get(3).label());
        assertEquals("Lighting", sitemapList.get(4).label());
        assertEquals("outside", sitemapList.get(5).label());
        assertEquals("Scenes", sitemapList.get(6).label());
        assertEquals("Schedule", sitemapList.get(7).label());

        Util.INSTANCE.sortSitemapList(sitemapList, "schedule");
        // Should be sorted, but "Schedule" should be the first one
        assertEquals("Schedule", sitemapList.get(0).label());
        assertEquals("Garden", sitemapList.get(1).label());
        assertEquals("Heating", sitemapList.get(2).label());
        assertEquals("Heatpump", sitemapList.get(3).label());
        assertEquals("i AM DEfault", sitemapList.get(4).label());
        assertEquals("Lighting", sitemapList.get(5).label());
        assertEquals("outside", sitemapList.get(6).label());
        assertEquals("Scenes", sitemapList.get(7).label());
    }

    private Document getSitemapOH1Document()
            throws ParserConfigurationException, IOException, SAXException {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<sitemaps>"

                + "<sitemap><name>default</name><label>i AM DEfault</label>"
                + "<link>http://myopenhab/rest/sitemaps/default</link>"
                + "<homepage><link>http://myopenhab/rest/sitemaps/default/default</link>"
                + "<leaf>false</leaf></homepage></sitemap>"

                + "<sitemap><name>heating</name><label>Heating</label>"
                + "<link>http://myopenhab/rest/sitemaps/heating</link>"
                + "<homepage><link>http://myopenhab/rest/sitemaps/heating/heating</link>"
                + "<leaf>false</leaf></homepage></sitemap>"

                + "<sitemap><name>lighting</name><label>Lighting</label>"
                + "<link>http://myopenhab/rest/sitemaps/lighting</link>"
                + "<homepage><link>http://myopenhab/rest/sitemaps/lighting/lighting</link>"
                + "<leaf>false</leaf></homepage></sitemap>"

                + "<sitemap><name>heatpump</name><label>Heatpump</label>"
                + "<link>http://myopenhab/rest/sitemaps/heatpump</link>"
                + "<homepage><link>http://myopenhab/rest/sitemaps/heatpump/heatpump</link>"
                + "<leaf>false</leaf></homepage></sitemap>"

                + "<sitemap><name>schedule</name><label>Schedule</label>"
                + "<link>http://myopenhab/rest/sitemaps/schedule</link>"
                + "<homepage><link>http://myopenhab/rest/sitemaps/schedule/schedule</link>"
                + "<leaf>false</leaf></homepage></sitemap>"

                + "<sitemap><name>outside</name><link>http://myopenhab/rest/sitemaps/outside</link>"
                + "<homepage><link>http://myopenhab/rest/sitemaps/outside/outside</link>"
                + "<leaf>false</leaf></homepage></sitemap>"

                + "<sitemap><name>garden</name><label>Garden</label>"
                + "<link>http://myopenhab/rest/sitemaps/garden</link>"
                + "<homepage><link>http://myopenhab/rest/sitemaps/garden/garden</link>"
                + "<leaf>false</leaf></homepage></sitemap>"

                + "<sitemap><name>scenes</name><label>Scenes</label>"
                + "<link>http://myopenhab/rest/sitemaps/scenes</link>"
                + "<homepage><link>http://myopenhab/rest/sitemaps/scenes/scenes</link>"
                + "<leaf>false</leaf></homepage></sitemap>"

                + "</sitemaps>";

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = dbf.newDocumentBuilder();
        return builder.parse(new InputSource(new StringReader(xml)));
    }


    @Test
    public void sitemapExists() throws Exception {
        assertTrue(Util.INSTANCE.sitemapExists(sitemapList(), "garden"));
        assertFalse(Util.INSTANCE.sitemapExists(sitemapList(), "monkies"));
        assertTrue("Sitemap \"demo\" is a \"normal\" one and exists",
                Util.INSTANCE.sitemapExists(Util.INSTANCE.parseSitemapList(createJsonArray(1)), "demo"));
        assertFalse("Sitemap \"_default\" exists on the server, "
                + "but isn't the only one => don't display it in the app.",
                Util.INSTANCE.sitemapExists(Util.INSTANCE.parseSitemapList(createJsonArray(1)), "_default"));
        assertFalse("Sitemap \"_default\" exists on the server, "
                + "but isn't the only one => don't display it in the app.",
                Util.INSTANCE.sitemapExists(Util.INSTANCE.parseSitemapList(createJsonArray(2)), "_default"));
        assertTrue("Sitemap \"_default\" exists on the server "
                + "and is the only one => display it in the app.",
                Util.INSTANCE.sitemapExists(Util.INSTANCE.parseSitemapList(createJsonArray(3)), "_default"));
    }

    private List<Sitemap> sitemapList()
            throws IOException, SAXException, ParserConfigurationException {
        return Util.INSTANCE.parseSitemapList(getSitemapOH1Document());
    }

    @Test
    public void getSitemapByName() throws Exception {
        assertEquals("i AM DEfault", Util.INSTANCE.getSitemapByName(sitemapList(), "default").label());
        assertEquals("outside", Util.INSTANCE.getSitemapByName(sitemapList(), "outside").label());
    }

    /**
     * @param id
     *             1: Two sitemaps, one "normal", one "_default"
     *             2: Three sitemaps, two "normal", one "_default"
     *             3: One "_default"
     * @return Sitemaps as jsonArray
     * @throws JSONException
     */
    private JSONArray createJsonArray(int id) throws JSONException {
        JSONArray result = new JSONArray();
        switch (id) {
            case 1:
                result.put(createTestJsonObject("demo", "Main Menu"));
                result.put(createTestJsonObject("_default", "Home"));
                break;
            case 2:
                result.put(createTestJsonObject("demo", "Main Menu"));
                result.put(createTestJsonObject("home", "HOME"));
                result.put(createTestJsonObject("test", null));
                result.put(createTestJsonObject("_default", "Home"));
                break;
            case 3:
                result.put(createTestJsonObject("_default", "Home"));
                break;
            default:
                throw new IllegalArgumentException("Wrong id");
        }
        return result;
    }

    private JSONObject createTestJsonObject(String name, String label) throws JSONException {
        JSONObject result = new JSONObject();
        result.put("name", name);
        if (label != null) {
            result.put("label", label);
        }
        result.put("link", "http://demo.openhab.org:8080/rest/sitemaps/" + name);

        JSONObject homepage = new JSONObject();
        homepage.put("link", "http://demo.openhab.org:8080/rest/sitemaps/" + name + "/" + name);
        homepage.put("leaf", false);
        homepage.put("timeout", false);
        homepage.put("widgets", new JSONArray());

        result.put("homepage", homepage);

        return result;
    }

    @Test
    public void testexceptionHasCause() {
        Exception cause = new CertPathValidatorException();
        Exception e = new SSLException(cause);

        assertTrue("The exception is caused by CertPathValidatorException, "
                + "so testexceptionHasCause() should return true",
                Util.INSTANCE.exceptionHasCause(e, CertPathValidatorException.class));
        assertFalse("The exception is not caused by ArrayIndexOutOfBoundsException, "
                + "so testexceptionHasCause() should return false",
                Util.INSTANCE.exceptionHasCause(e, ArrayIndexOutOfBoundsException.class));
    }

    @Test
    public void testObfuscateString() {
        assertEquals("abc***", Util.obfuscateString("abcdef"));
        assertEquals("abc", Util.obfuscateString("abc"));
        assertEquals("The function should not throw an exception, "
                + "when string length is shorter than clearTextCharCount",
                "a", Util.INSTANCE.obfuscateString("a", 10));
        assertEquals("a**", Util.INSTANCE.obfuscateString("abc", 1));
        assertEquals("***", Util.INSTANCE.obfuscateString("abc", 0));
    }
}