/*
 * Copyright (c) 2016-2018 Couchbase, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.couchbase.client.dcp;
import static com.couchbase.client.core.lang.backport.java.util.Objects.requireNonNull;


public class Credentials {


    private final String username;
    private final String password;

    public Credentials(String username, String password) {
        this.username = requireNonNull(username, "username can't be null");
        this.password = requireNonNull(password, "password can't be null");
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }
}
