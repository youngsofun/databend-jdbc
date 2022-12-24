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

package com.databend.client;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import static com.google.common.base.MoreObjects.toStringHelper;

public class QueryProgress {
    private final int rows;
    private final int bytes;

    @JsonCreator
    public QueryProgress(
            @JsonProperty("rows") int rows,
            @JsonProperty("bytes") int bytes) {
        this.rows = rows;
        this.bytes = bytes;
    }

    // add builder
    public static Builder builder() {
        return new Builder();
    }

    @JsonProperty
    public int getRows() {
        return rows;
    }

    @JsonProperty
    public int getBytes() {
        return bytes;
    }

    @Override
    public String toString() {
        return toStringHelper(this)
                .add("rows", rows)
                .add("bytes", bytes)
                .toString();
    }

    public static final class Builder {
        private int rows;
        private int bytes;

        public Builder setRows(int rows) {
            this.rows = rows;
            return this;
        }

        public Builder setBytes(int bytes) {
            this.bytes = bytes;
            return this;
        }

        public QueryProgress build() {
            return new QueryProgress(rows, bytes);
        }
    }
}
