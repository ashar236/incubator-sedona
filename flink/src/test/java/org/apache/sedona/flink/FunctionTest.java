/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sedona.flink;

import org.apache.commons.codec.binary.Hex;
import org.apache.flink.table.api.Table;
import org.apache.sedona.flink.expressions.Constructors;
import org.apache.sedona.flink.expressions.Functions;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;

import java.util.Arrays;
import java.util.Optional;

import static org.apache.flink.table.api.Expressions.$;
import static org.apache.flink.table.api.Expressions.call;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class FunctionTest extends TestBase{
    @BeforeClass
    public static void onceExecutedBeforeAll() {
        initialize();
    }

    @Test
    public void testBuffer() {
        Table pointTable = createPointTable_real(testDataSize);
        Table bufferTable = pointTable.select(call(Functions.ST_Buffer.class.getSimpleName(), $(pointColNames[0]), 1));
        Geometry result = (Geometry) first(bufferTable).getField(0);
        assert(result instanceof Polygon);
    }

    @Test
    public void testFlipCoordinates() {
        Table pointTable = createPointTable_real(testDataSize);
        Table flippedTable = pointTable.select(call(Functions.ST_FlipCoordinates.class.getSimpleName(), $(pointColNames[0])));
        Geometry result = (Geometry) first(flippedTable).getField(0);
        assertEquals("POINT (-118 32)", result.toString());
    }

    @Test
    public void testTransform() {
        Table pointTable = createPointTable_real(testDataSize);
        Table transformedTable = pointTable.select(call(Functions.ST_Transform.class.getSimpleName(), $(pointColNames[0])
                , "epsg:4326", "epsg:3857"));
        String result = first(transformedTable).getField(0).toString();
        assertEquals("POINT (-13135699.91360628 3763310.6271446524)", result);
    }

    @Test
    public void testDistance() {
        Table pointTable = createPointTable(testDataSize);
        pointTable = pointTable.select(call(Functions.ST_Distance.class.getSimpleName(), $(pointColNames[0])
                , call("ST_GeomFromWKT", "POINT (0 0)")));
        assertEquals(0.0, first(pointTable).getField(0));
    }

    @Test
    public void testYMax() {
        Table polygonTable = createPolygonTable(1);
        Table ResultTable = polygonTable.select(call(Functions.ST_YMax.class.getSimpleName(), $(polygonColNames[0])));
        assertNotNull(first(ResultTable).getField(0));
        double result = (double) first(ResultTable).getField(0);
        assertEquals(0.5, result,0);
    }

    @Test
    public void testYMin() {
        Table polygonTable = createPolygonTable(1);
        Table ResultTable = polygonTable.select(call(Functions.ST_YMin.class.getSimpleName(), $(polygonColNames[0])));
        assertNotNull(first(ResultTable).getField(0));
        double result = (double) first(ResultTable).getField(0);
        assertEquals(-0.5, result, 0);
    }


    @Test
    public void testGeomToGeoHash() {
        Table pointTable = createPointTable(testDataSize);
        pointTable = pointTable.select(
                call("ST_GeoHash", $(pointColNames[0]), 5)
        );
        assertEquals(first(pointTable).getField(0), "s0000");
    }

    @Test
    public void testPointOnSurface() {
        Table pointTable = createPointTable_real(testDataSize);
        Table surfaceTable = pointTable.select(call(Functions.ST_PointOnSurface.class.getSimpleName(), $(pointColNames[0])));
        Geometry result = (Geometry) first(surfaceTable).getField(0);
        assertEquals("POINT (32 -118)", result.toString());
    }

    @Test
    public void testReverse() {
        Table polygonTable = createPolygonTable(1);
        Table ReversedTable = polygonTable.select(call(Functions.ST_Reverse.class.getSimpleName(), $(polygonColNames[0])));
        Geometry result = (Geometry) first(ReversedTable).getField(0);
        assertEquals("POLYGON ((-0.5 -0.5, 0.5 -0.5, 0.5 0.5, -0.5 0.5, -0.5 -0.5))", result.toString());
    }

    @Test
    public void testPointN_positiveN() {
        int n = 1;
        Table polygonTable = createPolygonTable(1);
        Table linestringTable = polygonTable.select(call(Functions.ST_ExteriorRing.class.getSimpleName(), $(polygonColNames[0])));
        Table pointTable = linestringTable.select(call(Functions.ST_PointN.class.getSimpleName(), $("_c0"), n));
        Point point = (Point) first(pointTable).getField(0);
        assertNotNull(point);
        assertEquals("POINT (-0.5 -0.5)", point.toString());
    }

    @Test
    public void testPointN_negativeN() {
        int n = -3;
        Table polygonTable = createPolygonTable(1);
        Table linestringTable = polygonTable.select(call(Functions.ST_ExteriorRing.class.getSimpleName(), $(polygonColNames[0])));
        Table pointTable = linestringTable.select(call(Functions.ST_PointN.class.getSimpleName(), $("_c0"), n));
        Point point = (Point) first(pointTable).getField(0);
        assertNotNull(point);
        assertEquals("POINT (0.5 0.5)", point.toString());
    }

    @Test
    public void testExteriorRing() {
        Table polygonTable = createPolygonTable(1);
        Table linearRingTable = polygonTable.select(call(Functions.ST_ExteriorRing.class.getSimpleName(), $(polygonColNames[0])));
        LinearRing linearRing = (LinearRing) first(linearRingTable).getField(0);
        assertNotNull(linearRing);
        Assert.assertEquals("LINEARRING (-0.5 -0.5, -0.5 0.5, 0.5 0.5, 0.5 -0.5, -0.5 -0.5)", linearRing.toString());
    }

    @Test
    public void testAsEWKT() {
        Table polygonTable = createPolygonTable(testDataSize);
        polygonTable = polygonTable.select(call(Functions.ST_AsEWKT.class.getSimpleName(), $(polygonColNames[0])));
        String result = (String) first(polygonTable).getField(0);
        assertEquals("POLYGON ((-0.5 -0.5, -0.5 0.5, 0.5 0.5, 0.5 -0.5, -0.5 -0.5))", result);
    }

    @Test
    public void testAsText() {
        Table polygonTable = createPolygonTable(testDataSize);
        polygonTable = polygonTable.select(call(Functions.ST_AsText.class.getSimpleName(), $(polygonColNames[0])));
        String result = (String) first(polygonTable).getField(0);
        assertEquals("POLYGON ((-0.5 -0.5, -0.5 0.5, 0.5 0.5, 0.5 -0.5, -0.5 -0.5))", result);
    }

    @Test
    public void testAsEWKB() {
        Table polygonTable = createPolygonTable(testDataSize);
        polygonTable = polygonTable.select(call(Functions.ST_AsEWKB.class.getSimpleName(), $(polygonColNames[0])));
        String result = Hex.encodeHexString((byte[]) first(polygonTable).getField(0));
        assertEquals("01030000000100000005000000000000000000e0bf000000000000e0bf000000000000e0bf000000000000e03f000000000000e03f000000000000e03f000000000000e03f000000000000e0bf000000000000e0bf000000000000e0bf", result);
    }

    @Test
    public void testAsBinary() {
        Table polygonTable = createPolygonTable(testDataSize);
        polygonTable = polygonTable.select(call(Functions.ST_AsBinary.class.getSimpleName(), $(polygonColNames[0])));
        String result = Hex.encodeHexString((byte[]) first(polygonTable).getField(0));
        assertEquals("01030000000100000005000000000000000000e0bf000000000000e0bf000000000000e0bf000000000000e03f000000000000e03f000000000000e03f000000000000e03f000000000000e0bf000000000000e0bf000000000000e0bf", result);
    }

    @Test
    public void testGeoJSON() {
        Table polygonTable = createPolygonTable(testDataSize);
        polygonTable = polygonTable.select(call(Functions.ST_AsGeoJSON.class.getSimpleName(), $(polygonColNames[0])));
        String result = (String) first(polygonTable).getField(0);
        assertEquals("{\"type\":\"Polygon\",\"coordinates\":[[[-0.5,-0.5],[-0.5,0.5],[0.5,0.5],[0.5,-0.5],[-0.5,-0.5]]]}", result);
    }

    @Test
    public void testForce2D() {
        Table polygonTable = createPolygonTable(1);
        Table Forced2DTable = polygonTable.select(call(Functions.ST_Force_2D.class.getSimpleName(), $(polygonColNames[0])));
        Geometry result = (Geometry) first(Forced2DTable).getField(0);
        assertEquals("POLYGON ((-0.5 -0.5, -0.5 0.5, 0.5 0.5, 0.5 -0.5, -0.5 -0.5))", result.toString());
    }

    @Test
    public void testIsEmpty() {
        Table polygonTable = createPolygonTable(testDataSize);
        polygonTable = polygonTable.select(call(Functions.ST_IsEmpty.class.getSimpleName(), $(polygonColNames[0])));
        boolean result = (boolean) first(polygonTable).getField(0);
        assertEquals(false, result);
    }

    @Test
    public void testXMax() {
        Table polygonTable = createPolygonTable(1);
        Table MaxTable = polygonTable.select(call(Functions.ST_XMax.class.getSimpleName(), $(polygonColNames[0])));
        double result = (double) first(MaxTable).getField(0);
        assertEquals(0.5, result,0);
    }

    @Test
    public void testXMin() {
        Table polygonTable = createPolygonTable(1);
        Table MinTable = polygonTable.select(call(Functions.ST_XMin.class.getSimpleName(), $(polygonColNames[0])));
        double result = (double) first(MinTable).getField(0);
        assertEquals(-0.5, result,0);
    }

    @Test
    public void testBuildArea() {
        Table polygonTable = createPolygonTable(1);
        Table arealGeomTable = polygonTable.select(call(Functions.ST_BuildArea.class.getSimpleName(), $(polygonColNames[0])));
        Geometry result = (Geometry) first(arealGeomTable).getField(0);
        assertEquals("POLYGON ((-0.5 -0.5, -0.5 0.5, 0.5 0.5, 0.5 -0.5, -0.5 -0.5))", result.toString());
    }

    @Test
    public void testSetSRID() {
        Table polygonTable = createPolygonTable(1);
        polygonTable = polygonTable
                .select(call(Functions.ST_SetSRID.class.getSimpleName(), $(polygonColNames[0]), 3021))
                .select(call(Functions.ST_SRID.class.getSimpleName(), $("_c0")));
        int result = (int) first(polygonTable).getField(0);
        assertEquals(3021, result);
    }

    @Test
    public void testSRID() {
        Table polygonTable = createPolygonTable(1);
        polygonTable = polygonTable.select(call(Functions.ST_SRID.class.getSimpleName(), $(polygonColNames[0])));
        int result = (int) first(polygonTable).getField(0);
        assertEquals(0, result);
    }

    @Test
    public void testIsClosedForOpen() {
        Table linestringTable = createLineStringTable(1);
        linestringTable = linestringTable.select(call(Functions.ST_IsClosed.class.getSimpleName(), $(linestringColNames[0])));
        assertFalse((boolean) first(linestringTable).getField(0));
    }

    @Test
    public void testIsClosedForClosed() {
        Table polygonTable = createPolygonTable(1);
        polygonTable = polygonTable.select(call(Functions.ST_IsClosed.class.getSimpleName(), $(polygonColNames[0])));
        assertTrue((boolean) first(polygonTable).getField(0));
    }

    @Test
    public void testIsRingForRing() {
        Table polygonTable = createPolygonTable(1);
        Table linestringTable = polygonTable.select(call(Functions.ST_ExteriorRing.class.getSimpleName(), $(polygonColNames[0])));
        linestringTable = linestringTable.select(call(Functions.ST_IsRing.class.getSimpleName(), $("_c0")));
        assertTrue((boolean) first(linestringTable).getField(0));
    }

    @Test
    public void testIsRingForNonRing() {
        Table linestringTable = createLineStringTable(1);
        linestringTable = linestringTable.select(call(Functions.ST_IsClosed.class.getSimpleName(), $(linestringColNames[0])));
        assertFalse((boolean) first(linestringTable).getField(0));
    }

    @Test
    public void testIsSimple() {
        Table polygonTable = createPolygonTable(1);
        polygonTable = polygonTable.select(call(Functions.ST_IsSimple.class.getSimpleName(), $(polygonColNames[0])));
        assertTrue((boolean) first(polygonTable).getField(0));
    }

    @Test
    public void testIsValid() {
        Table polygonTable = createPolygonTable(1);
        polygonTable = polygonTable.select(call(Functions.ST_IsValid.class.getSimpleName(), $(polygonColNames[0])));
        assertTrue((boolean) first(polygonTable).getField(0));
    }
}
