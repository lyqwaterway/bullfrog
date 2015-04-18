/*
 * Copyright 2012-2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.collector;

import java.io.IOException;

import javax.annotation.Nullable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.io.CharSource;

import org.glowroot.common.ObjectMappers;
import org.glowroot.transaction.model.Profile;
import org.glowroot.transaction.model.ProfileNode;

public class ProfileCharSourceCreator {

    private static final ObjectMapper mapper = ObjectMappers.create();

    private ProfileCharSourceCreator() {}

    public static @Nullable CharSource createProfileCharSource(@Nullable Profile profile)
            throws IOException {
        if (profile == null) {
            return null;
        }
        synchronized (profile.getLock()) {
            String profileJson = createProfileJson(profile.getSyntheticRootNode());
            if (profileJson == null) {
                return null;
            }
            return CharSource.wrap(profileJson);
        }
    }

    static @Nullable String createProfileJson(ProfileNode syntheticRootNode) throws IOException {
        ProfileNode rootNode;
        if (syntheticRootNode.getChildNodes().isEmpty()) {
            return null;
        } else if (syntheticRootNode.getChildNodes().size() == 1) {
            // strip off synthetic root node since only one real root node
            rootNode = syntheticRootNode.getChildNodes().get(0);
        } else {
            rootNode = syntheticRootNode;
        }
        // need to convert profile into bytes entirely inside of the above lock (no lazy CharSource)
        return mapper.writeValueAsString(rootNode);
    }
}
