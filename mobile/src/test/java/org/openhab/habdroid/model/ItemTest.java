package org.openhab.habdroid.model;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.runners.MockitoJUnitRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(MockitoJUnitRunner.class)
public class ItemTest {
    @Test
    public void getStateAsBoolean_stateOff_returnFalse() throws Exception {
        Item sut = Item.Companion.fromJson(itemJsonForState("OFF"));
        assertNotNull(sut.state());
        assertFalse(sut.state().asBoolean());
    }

    @Test
         public void getStateAsBoolean_stateON_returnTrue() throws Exception {
        Item sut = Item.Companion.fromJson(itemJsonForState("ON"));
        assertNotNull(sut.state());
        assertTrue(sut.state().asBoolean());
    }

    @Test
    public void getStateAsBoolean_stateNegativeInteger_returnFalse() throws Exception {
        Item sut = Item.Companion.fromJson(itemJsonForState("-42"));
        assertNotNull(sut.state());
        assertFalse(sut.state().asBoolean());
    }

    @Test
    public void getStateAsBoolean_statePositiveInteger_returnTrue() throws Exception {
        Item sut = Item.Companion.fromJson(itemJsonForState("42"));
        assertNotNull(sut.state());
        assertTrue(sut.state().asBoolean());
    }

    @Test
    public void getStateAsBoolean_stateIsZero_returnFalse() throws Exception {
        Item sut = Item.Companion.fromJson(itemJsonForState("0"));
        assertNotNull(sut.state());
        assertFalse(sut.state().asBoolean());
    }

    @Test
    public void getStateAsBoolean_stateHsbBrightnessZero_returnFalse() throws Exception {
        Item sut = Item.Companion.fromJson(itemJsonForState("10,10,0"));
        assertNotNull(sut.state());
        assertFalse(sut.state().asBoolean());
    }

    @Test
    public void getStateAsBoolean_stateHsbBrightnessPositive_returnTrue() throws Exception {
        Item sut = Item.Companion.fromJson(itemJsonForState("10,10,50"));
        assertNotNull(sut.state());
        assertTrue(sut.state().asBoolean());
    }

    private JSONObject itemJsonForState(String state) throws JSONException {
        String statePart = state != null ? ", state: '" + state + "'" : "";
        return new JSONObject("{ 'name': 'foo', 'type': Dummy'" + statePart + " }");
    }

    @Test
    public void isReadOnly() throws Exception {
        JSONObject object = new JSONObject();
        object.put("name", "TestItem");
        object.put("type",  "Dummy");
        assertFalse(Item.Companion.fromJson(object).readOnly());

        object.put("stateDescription", new JSONObject().put("readOnly", true));
        assertTrue(Item.Companion.fromJson(object).readOnly());

        object.put("stateDescription", new JSONObject().put("readOnly", false));
        assertFalse(Item.Companion.fromJson(object).readOnly());
    }

    @Test
    public void getMembers() throws Exception {
        Item sut = Item.Companion.fromJson(new JSONObject("{ 'members': ["
                + "{ 'state': '52.5200066,13.4029540', 'type': 'Location',"
                + "'name': 'GroupDemoLocation', 'label': 'Location 1',"
                + "'groupNames': [ 'LocationGroup' ] },"
                + "{ 'state': '52.5200066,13.4029540', 'type': 'Location',"
                + "'name': 'GroupDemoLocation', 'label': 'Location 2',"
                + "'groupNames': [ 'LocationGroup' ] },"
                + "], 'state': 'NULL', 'type': 'Group',"
                + "'name': 'LocationGroup', 'label': 'Location Group' }"));
        assertEquals(2, sut.members().size());
        assertEquals(Item.Type.Location, sut.members().get(0).type());
        assertEquals("Location 2", sut.members().get(1).label());
    }
}