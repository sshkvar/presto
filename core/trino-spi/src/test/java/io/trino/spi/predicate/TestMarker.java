/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.spi.predicate;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Ordering;
import org.testng.annotations.Test;

import java.util.Map;

import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.DoubleType.DOUBLE;
import static io.trino.spi.type.RealType.REAL;
import static java.lang.Float.floatToIntBits;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

public class TestMarker
{
    @Test
    public void testTypes()
    {
        assertEquals(Marker.lowerUnbounded(BIGINT).getType(), BIGINT);
        assertEquals(Marker.below(BIGINT, 1L).getType(), BIGINT);
        assertEquals(Marker.exactly(BIGINT, 1L).getType(), BIGINT);
        assertEquals(Marker.above(BIGINT, 1L).getType(), BIGINT);
        assertEquals(Marker.upperUnbounded(BIGINT).getType(), BIGINT);
    }

    @Test
    public void testUnbounded()
    {
        assertTrue(Marker.lowerUnbounded(BIGINT).isLowerUnbounded());
        assertFalse(Marker.lowerUnbounded(BIGINT).isUpperUnbounded());
        assertTrue(Marker.upperUnbounded(BIGINT).isUpperUnbounded());
        assertFalse(Marker.upperUnbounded(BIGINT).isLowerUnbounded());

        assertFalse(Marker.below(BIGINT, 1L).isLowerUnbounded());
        assertFalse(Marker.below(BIGINT, 1L).isUpperUnbounded());
        assertFalse(Marker.exactly(BIGINT, 1L).isLowerUnbounded());
        assertFalse(Marker.exactly(BIGINT, 1L).isUpperUnbounded());
        assertFalse(Marker.above(BIGINT, 1L).isLowerUnbounded());
        assertFalse(Marker.above(BIGINT, 1L).isUpperUnbounded());
    }

    @Test
    public void testComparisons()
    {
        ImmutableList<Marker> markers = ImmutableList.of(
                Marker.lowerUnbounded(BIGINT),
                Marker.above(BIGINT, 0L),
                Marker.below(BIGINT, 1L),
                Marker.exactly(BIGINT, 1L),
                Marker.above(BIGINT, 1L),
                Marker.below(BIGINT, 2L),
                Marker.upperUnbounded(BIGINT));

        assertTrue(Ordering.natural().isStrictlyOrdered(markers));

        // Compare every marker with every other marker
        // Since the markers are strictly ordered, the value of the comparisons should be equivalent to the comparisons
        // of their indexes.
        for (int i = 0; i < markers.size(); i++) {
            for (int j = 0; j < markers.size(); j++) {
                assertTrue(markers.get(i).compareTo(markers.get(j)) == Integer.compare(i, j));
            }
        }
    }

    @Test
    public void testAdjacency()
    {
        ImmutableMap<Marker, Integer> markers = ImmutableMap.<Marker, Integer>builder()
                .put(Marker.lowerUnbounded(BIGINT), -1000)
                .put(Marker.above(BIGINT, 0L), -100)
                .put(Marker.below(BIGINT, 1L), -1)
                .put(Marker.exactly(BIGINT, 1L), 0)
                .put(Marker.above(BIGINT, 1L), 1)
                .put(Marker.below(BIGINT, 2L), 100)
                .put(Marker.upperUnbounded(BIGINT), 1000)
                .build();

        // Compare every marker with every other marker
        // Map values of distance 1 indicate expected adjacency
        for (Map.Entry<Marker, Integer> entry1 : markers.entrySet()) {
            for (Map.Entry<Marker, Integer> entry2 : markers.entrySet()) {
                boolean adjacent = entry1.getKey().isAdjacent(entry2.getKey());
                boolean distanceIsOne = Math.abs(entry1.getValue() - entry2.getValue()) == 1;
                assertEquals(adjacent, distanceIsOne);
            }
        }

        assertEquals(Marker.below(BIGINT, 1L).greaterAdjacent(), Marker.exactly(BIGINT, 1L));
        assertEquals(Marker.exactly(BIGINT, 1L).greaterAdjacent(), Marker.above(BIGINT, 1L));
        assertEquals(Marker.above(BIGINT, 1L).lesserAdjacent(), Marker.exactly(BIGINT, 1L));
        assertEquals(Marker.exactly(BIGINT, 1L).lesserAdjacent(), Marker.below(BIGINT, 1L));

        try {
            Marker.below(BIGINT, 1L).lesserAdjacent();
            fail();
        }
        catch (IllegalStateException e) {
        }

        try {
            Marker.above(BIGINT, 1L).greaterAdjacent();
            fail();
        }
        catch (IllegalStateException e) {
        }

        try {
            Marker.lowerUnbounded(BIGINT).lesserAdjacent();
            fail();
        }
        catch (IllegalStateException e) {
        }

        try {
            Marker.lowerUnbounded(BIGINT).greaterAdjacent();
            fail();
        }
        catch (IllegalStateException e) {
        }

        try {
            Marker.upperUnbounded(BIGINT).lesserAdjacent();
            fail();
        }
        catch (IllegalStateException e) {
        }

        try {
            Marker.upperUnbounded(BIGINT).greaterAdjacent();
            fail();
        }
        catch (IllegalStateException e) {
        }
    }

    @Test
    public void testDoubleNaN()
    {
        assertThatThrownBy(() -> Marker.above(DOUBLE, Double.NaN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Marker.exactly(DOUBLE, Double.NaN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Marker.below(DOUBLE, Double.NaN)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void testRealNaN()
    {
        assertThatThrownBy(() -> Marker.above(REAL, (long) floatToIntBits(Float.NaN))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Marker.exactly(REAL, (long) floatToIntBits(Float.NaN))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Marker.below(REAL, (long) floatToIntBits(Float.NaN))).isInstanceOf(IllegalArgumentException.class);
    }
}
