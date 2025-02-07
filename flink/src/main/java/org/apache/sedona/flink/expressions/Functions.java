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
package org.apache.sedona.flink.expressions;

import org.apache.flink.table.annotation.DataTypeHint;
import org.apache.flink.table.functions.ScalarFunction;
import org.apache.sedona.common.utils.GeomUtils;
import org.geotools.geometry.jts.JTS;
import org.geotools.referencing.CRS;
import org.locationtech.jts.io.WKTWriter;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.MathTransform;
import org.opengis.referencing.operation.TransformException;
import org.wololo.jts2geojson.GeoJSONWriter;
import scala.Option;

import static org.locationtech.jts.geom.Coordinate.NULL_ORDINATE;

public class Functions {
    public static class ST_Buffer extends ScalarFunction {
        @DataTypeHint(value = "RAW", bridgedTo = org.locationtech.jts.geom.Geometry.class)
        public Geometry eval(@DataTypeHint(value = "RAW", bridgedTo = org.locationtech.jts.geom.Geometry.class)
                Object o, @DataTypeHint("Double") Double radius) {
            Geometry geom = (Geometry) o;
            return org.apache.sedona.common.Functions.buffer(geom, radius);
        }
    }

    public static class ST_Distance extends ScalarFunction {
        @DataTypeHint("Double")
        public Double eval(@DataTypeHint(value = "RAW", bridgedTo = org.locationtech.jts.geom.Geometry.class) Object o1,
                @DataTypeHint(value = "RAW", bridgedTo = org.locationtech.jts.geom.Geometry.class) Object o2) {
            Geometry geom1 = (Geometry) o1;
            Geometry geom2 = (Geometry) o2;
            return org.apache.sedona.common.Functions.distance(geom1, geom2);
        }
    }

    public static class ST_YMin extends ScalarFunction {
        @DataTypeHint("Double")
        public Double eval(@DataTypeHint(value = "RAW", bridgedTo = org.locationtech.jts.geom.Geometry.class) Object o){
            Geometry geom = (Geometry) o;
            return org.apache.sedona.common.Functions.yMin(geom);
        }
    }

    public static class ST_YMax extends ScalarFunction {
        @DataTypeHint("Double")
        public Double eval(@DataTypeHint(value = "RAW", bridgedTo = org.locationtech.jts.geom.Geometry.class) Object o){
            Geometry geom = (Geometry) o;
            return org.apache.sedona.common.Functions.yMax(geom);
        }
    }


    public static class ST_Transform extends ScalarFunction {
        @DataTypeHint(value = "RAW", bridgedTo = org.locationtech.jts.geom.Geometry.class)
        public Geometry eval(@DataTypeHint(value = "RAW", bridgedTo = org.locationtech.jts.geom.Geometry.class) Object o, @DataTypeHint("String") String sourceCRS, @DataTypeHint("String") String targetCRS)
            throws FactoryException, TransformException {
            Geometry geom = (Geometry) o;
            return org.apache.sedona.common.Functions.transform(geom, sourceCRS, targetCRS);
        }
    }

    public static class ST_FlipCoordinates extends ScalarFunction {
        @DataTypeHint(value = "RAW", bridgedTo = org.locationtech.jts.geom.Geometry.class)
        public Geometry eval(@DataTypeHint(value = "RAW", bridgedTo = org.locationtech.jts.geom.Geometry.class) Object o) {
            Geometry geom = (Geometry) o;
            return org.apache.sedona.common.Functions.flipCoordinates(geom);
        }
    }

    public static class ST_GeoHash extends ScalarFunction {
        @DataTypeHint("String")
        public String eval(@DataTypeHint(value = "RAW", bridgedTo = org.locationtech.jts.geom.Geometry.class) Object geometry, Integer precision) {
            Geometry geom = (Geometry) geometry;
            return org.apache.sedona.common.Functions.geohash(geom, precision);
        }
    }

    public static class ST_PointOnSurface extends ScalarFunction {
        @DataTypeHint(value = "RAW", bridgedTo = org.locationtech.jts.geom.Geometry.class)
        public Geometry eval(@DataTypeHint(value = "RAW", bridgedTo = org.locationtech.jts.geom.Geometry.class) Object o) {
            Geometry geom = (Geometry) o;
            return org.apache.sedona.common.Functions.pointOnSurface(geom);
        }
    }

    public static class ST_Reverse extends ScalarFunction {
        @DataTypeHint(value = "RAW", bridgedTo = org.locationtech.jts.geom.Geometry.class)
        public Geometry eval(@DataTypeHint(value = "RAW", bridgedTo = org.locationtech.jts.geom.Geometry.class) Object o) {
            Geometry geom = (Geometry) o;
            return org.apache.sedona.common.Functions.reverse(geom);
        }
    }

    public static class ST_PointN extends ScalarFunction {
        @DataTypeHint(value = "RAW", bridgedTo = org.locationtech.jts.geom.Geometry.class)
        public Geometry eval(@DataTypeHint(value = "RAW", bridgedTo = org.locationtech.jts.geom.Geometry.class) Object o, int n) {
            Geometry geom = (Geometry) o;
            return org.apache.sedona.common.Functions.pointN(geom, n);
        }
    }

    public static class ST_ExteriorRing extends ScalarFunction {
        @DataTypeHint(value = "RAW", bridgedTo = org.locationtech.jts.geom.Geometry.class)
        public Geometry eval(@DataTypeHint(value = "RAW", bridgedTo = org.locationtech.jts.geom.Geometry.class) Object o) {
            Geometry geom = (Geometry) o;
            return org.apache.sedona.common.Functions.exteriorRing(geom);
        }
    }

    public static class ST_AsEWKT extends ScalarFunction {
        @DataTypeHint("String")
        public String eval(@DataTypeHint(value = "RAW", bridgedTo = org.locationtech.jts.geom.Geometry.class) Object o) {
            Geometry geom = (Geometry) o;
            return org.apache.sedona.common.Functions.asEWKT(geom);
        }
    }

    public static class ST_AsText extends ScalarFunction {
        @DataTypeHint("String")
        public String eval(@DataTypeHint(value = "RAW", bridgedTo = org.locationtech.jts.geom.Geometry.class) Object o) {
            Geometry geom = (Geometry) o;
            return org.apache.sedona.common.Functions.asEWKT(geom);
        }
    }

    public static class ST_AsEWKB extends ScalarFunction {
        @DataTypeHint("Bytes")
        public byte[] eval(@DataTypeHint(value = "RAW", bridgedTo = org.locationtech.jts.geom.Geometry.class) Object o) {
            Geometry geom = (Geometry) o;
            return org.apache.sedona.common.Functions.asEWKB(geom);
        }
    }

    public static class ST_AsBinary extends ScalarFunction {
        @DataTypeHint("Bytes")
        public byte[] eval(@DataTypeHint(value = "RAW", bridgedTo = org.locationtech.jts.geom.Geometry.class) Object o) {
            Geometry geom = (Geometry) o;
            return org.apache.sedona.common.Functions.asEWKB(geom);
        }
    }

    public static class ST_AsGeoJSON extends ScalarFunction {
        @DataTypeHint("String")
        public String eval(@DataTypeHint(value = "RAW", bridgedTo = org.locationtech.jts.geom.Geometry.class) Object o) {
            Geometry geom = (Geometry) o;
            return org.apache.sedona.common.Functions.asGeoJson(geom);
        }
    }

    public static class ST_Force_2D extends ScalarFunction {
        @DataTypeHint(value = "RAW", bridgedTo = org.locationtech.jts.geom.Geometry.class)
        public Geometry eval(@DataTypeHint(value = "RAW", bridgedTo = org.locationtech.jts.geom.Geometry.class) Object o) {
            Geometry geom = (Geometry) o;
            return org.apache.sedona.common.Functions.force2D(geom);
        }
    }

    public static class ST_IsEmpty extends ScalarFunction {
        @DataTypeHint("Boolean")
        public boolean eval(@DataTypeHint(value = "RAW", bridgedTo = org.locationtech.jts.geom.Geometry.class) Object o) {
            Geometry geom = (Geometry) o;
            return org.apache.sedona.common.Functions.isEmpty(geom);
        }
    }

    public static class ST_XMax extends ScalarFunction {
        @DataTypeHint("Double")
        public Double eval(@DataTypeHint(value = "RAW", bridgedTo = org.locationtech.jts.geom.Geometry.class) Object o) {
            Geometry geom = (Geometry) o;
            return org.apache.sedona.common.Functions.xMax(geom);
        }
    }

    public static class ST_XMin extends ScalarFunction {
        @DataTypeHint("Double")
        public Double eval(@DataTypeHint(value = "RAW", bridgedTo = org.locationtech.jts.geom.Geometry.class) Object o) {
            Geometry geom = (Geometry) o;
            return org.apache.sedona.common.Functions.xMin(geom);
        }
    }

    public static class ST_BuildArea extends ScalarFunction {
        @DataTypeHint(value = "RAW", bridgedTo = org.locationtech.jts.geom.Geometry.class)
        public Geometry eval(@DataTypeHint(value = "RAW", bridgedTo = org.locationtech.jts.geom.Geometry.class) Object o) {
            Geometry geom = (Geometry) o;
            return org.apache.sedona.common.Functions.buildArea(geom);
        }
    }

    public static class ST_SetSRID extends ScalarFunction {
        @DataTypeHint(value = "RAW", bridgedTo = org.locationtech.jts.geom.Geometry.class)
        public Geometry eval(@DataTypeHint(value = "RAW", bridgedTo = org.locationtech.jts.geom.Geometry.class) Object o, int srid) {
            Geometry geom = (Geometry) o;
            return org.apache.sedona.common.Functions.setSRID(geom, srid);
        }
    }

    public static class ST_SRID extends ScalarFunction {
        @DataTypeHint("Integer")
        public Integer eval(@DataTypeHint(value = "RAW", bridgedTo = org.locationtech.jts.geom.Geometry.class) Object o) {
            Geometry geom = (Geometry) o;
            return org.apache.sedona.common.Functions.getSRID(geom);
        }
    }

    public static class ST_IsClosed extends ScalarFunction {
        @DataTypeHint("Boolean")
        public boolean eval(@DataTypeHint(value = "RAW", bridgedTo = org.locationtech.jts.geom.Geometry.class) Object o) {
            Geometry geom = (Geometry) o;
            return org.apache.sedona.common.Functions.isClosed(geom);
        }
    }

    public static class ST_IsRing extends ScalarFunction {
        @DataTypeHint("Boolean")
        public boolean eval(@DataTypeHint(value = "RAW", bridgedTo = org.locationtech.jts.geom.Geometry.class) Object o) {
            Geometry geom = (Geometry) o;
            return org.apache.sedona.common.Functions.isRing(geom);
        }
    }

    public static class ST_IsSimple extends ScalarFunction {
        @DataTypeHint("Boolean")
        public boolean eval(@DataTypeHint(value = "RAW", bridgedTo = org.locationtech.jts.geom.Geometry.class) Object o) {
            Geometry geom = (Geometry) o;
            return org.apache.sedona.common.Functions.isSimple(geom);
        }
    }

    public static class ST_IsValid extends ScalarFunction {
        @DataTypeHint("Boolean")
        public boolean eval(@DataTypeHint(value = "RAW", bridgedTo = org.locationtech.jts.geom.Geometry.class) Object o) {
            Geometry geom = (Geometry) o;
            return org.apache.sedona.common.Functions.isValid(geom);
        }
    }
}
