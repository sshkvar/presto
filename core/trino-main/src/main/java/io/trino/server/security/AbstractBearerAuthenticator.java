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
package io.trino.server.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.trino.spi.security.BasicPrincipal;
import io.trino.spi.security.Identity;

import javax.ws.rs.container.ContainerRequestContext;

import static com.google.common.base.Strings.nullToEmpty;
import static com.google.common.net.HttpHeaders.AUTHORIZATION;
import static java.util.Objects.requireNonNull;

public abstract class AbstractBearerAuthenticator
        implements Authenticator
{
    private final UserMapping userMapping;

    protected AbstractBearerAuthenticator(UserMapping userMapping)
    {
        this.userMapping = requireNonNull(userMapping, "userMapping is null");
    }

    @Override
    public Identity authenticate(ContainerRequestContext request)
            throws AuthenticationException
    {
        String header = nullToEmpty(request.getHeaders().getFirst(AUTHORIZATION));

        int space = header.indexOf(' ');
        if ((space < 0) || !header.substring(0, space).equalsIgnoreCase("bearer")) {
            throw needAuthentication(request, null);
        }
        String token = header.substring(space + 1).trim();
        if (token.isEmpty()) {
            throw needAuthentication(request, null);
        }

        try {
            Jws<Claims> claimsJws = parseClaimsJws(token);
            String subject = claimsJws.getBody().getSubject();
            String authenticatedUser = userMapping.mapUser(subject);
            return Identity.forUser(authenticatedUser)
                    .withPrincipal(new BasicPrincipal(subject))
                    .build();
        }
        catch (JwtException | UserMappingException e) {
            throw needAuthentication(request, e.getMessage());
        }
        catch (RuntimeException e) {
            throw new RuntimeException("Authentication error", e);
        }
    }

    protected abstract Jws<Claims> parseClaimsJws(String jws);

    protected abstract AuthenticationException needAuthentication(ContainerRequestContext request, String message);
}
