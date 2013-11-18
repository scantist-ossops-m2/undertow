/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
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

package io.undertow.servlet.spec;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.Charset;
import java.nio.charset.UnsupportedCharsetException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.servlet.AsyncContext;
import javax.servlet.DispatcherType;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletRequestWrapper;
import javax.servlet.ServletResponse;
import javax.servlet.ServletResponseWrapper;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpUpgradeHandler;
import javax.servlet.http.Part;

import io.undertow.security.api.SecurityContext;
import io.undertow.security.idm.Account;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.form.FormData;
import io.undertow.server.handlers.form.FormDataParser;
import io.undertow.server.handlers.form.MultiPartParserDefinition;
import io.undertow.server.session.SessionConfig;
import io.undertow.servlet.UndertowServletMessages;
import io.undertow.servlet.api.InstanceFactory;
import io.undertow.servlet.api.InstanceHandle;
import io.undertow.servlet.api.SecurityRoleRef;
import io.undertow.servlet.core.ManagedServlet;
import io.undertow.servlet.core.ServletUpgradeListener;
import io.undertow.servlet.handlers.ServletRequestContext;
import io.undertow.servlet.handlers.ServletChain;
import io.undertow.servlet.handlers.ServletPathMatch;
import io.undertow.servlet.util.EmptyEnumeration;
import io.undertow.servlet.util.IteratorEnumeration;
import io.undertow.util.CanonicalPathUtils;
import io.undertow.util.DateUtils;
import io.undertow.util.HeaderMap;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import io.undertow.util.LocaleUtils;
import io.undertow.util.Methods;
import org.xnio.LocalSocketAddress;

/**
 * The http servlet request implementation. This class is not thread safe
 *
 * @author Stuart Douglas
 */
public final class HttpServletRequestImpl implements HttpServletRequest {

    private static final Charset DEFAULT_CHARSET = Charset.forName("ISO-8859-1");

    private final HttpServerExchange exchange;
    private final ServletContextImpl originalServletContext;
    private ServletContextImpl servletContext;

    private Map<String, Object> attributes = null;

    private ServletInputStream servletInputStream;
    private BufferedReader reader;

    private Cookie[] cookies;
    private List<Part> parts = null;
    private volatile boolean asyncStarted = false;
    private volatile AsyncContextImpl asyncContext = null;
    private Map<String, Deque<String>> queryParameters;
    private FormData parsedFormData;
    private Charset characterEncoding;
    private boolean readStarted;
    private SessionConfig.SessionCookieSource sessionCookieSource;

    public HttpServletRequestImpl(final HttpServerExchange exchange, final ServletContextImpl servletContext) {
        this.exchange = exchange;
        this.servletContext = servletContext;
        this.originalServletContext = servletContext;
    }

    public HttpServerExchange getExchange() {
        return exchange;
    }

    @Override
    public String getAuthType() {
        SecurityContext securityContext = exchange.getAttachment(SecurityContext.ATTACHMENT_KEY);

        return securityContext != null ? securityContext.getMechanismName() : null;
    }

    @Override
    public Cookie[] getCookies() {
        if (cookies == null) {
            Map<String, io.undertow.server.handlers.Cookie> cookies = exchange.getRequestCookies();
            if (cookies.isEmpty()) {
                return null;
            }
            Cookie[] value = new Cookie[cookies.size()];
            int i = 0;
            for (Map.Entry<String, io.undertow.server.handlers.Cookie> entry : cookies.entrySet()) {
                io.undertow.server.handlers.Cookie cookie = entry.getValue();
                Cookie c = new Cookie(cookie.getName(), cookie.getValue());
                if (cookie.getDomain() != null) {
                    c.setDomain(cookie.getDomain());
                }
                c.setHttpOnly(cookie.isHttpOnly());
                if (cookie.getMaxAge() != null) {
                    c.setMaxAge(cookie.getMaxAge());
                }
                if (cookie.getPath() != null) {
                    c.setPath(cookie.getPath());
                }
                c.setSecure(cookie.isSecure());
                c.setVersion(cookie.getVersion());
                value[i++] = c;
            }
            this.cookies = value;
        }
        return cookies;
    }

    @Override
    public long getDateHeader(final String name) {
        String header = exchange.getRequestHeaders().getFirst(name);
        if (header == null) {
            return -1;
        }
        Date date = DateUtils.parseDate(header);
        if (date == null) {
            throw UndertowServletMessages.MESSAGES.headerCannotBeConvertedToDate(header);
        }
        return date.getTime();
    }

    @Override
    public String getHeader(final String name) {
        HeaderMap headers = exchange.getRequestHeaders();
        return headers.getFirst(name);
    }

    public String getHeader(final HttpString name) {
        HeaderMap headers = exchange.getRequestHeaders();
        return headers.getFirst(name);
    }


    @Override
    public Enumeration<String> getHeaders(final String name) {
        List<String> headers = exchange.getRequestHeaders().get(name);
        if (headers == null) {
            return EmptyEnumeration.instance();
        }
        return new IteratorEnumeration<String>(headers.iterator());
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        final Set<String> headers = new HashSet<String>();
        for (final HttpString i : exchange.getRequestHeaders().getHeaderNames()) {
            headers.add(i.toString());
        }
        return new IteratorEnumeration<String>(headers.iterator());
    }

    @Override
    public int getIntHeader(final String name) {
        String header = getHeader(name);
        if (header == null) {
            return -1;
        }
        return Integer.parseInt(header);
    }

    @Override
    public String getMethod() {
        return exchange.getRequestMethod().toString();
    }

    @Override
    public String getPathInfo() {
        ServletPathMatch match = exchange.getAttachment(ServletRequestContext.ATTACHMENT_KEY).getServletPathMatch();
        if (match != null) {
            return match.getRemaining();
        }
        return null;
    }

    @Override
    public String getPathTranslated() {
        return getRealPath(getPathInfo());
    }

    @Override
    public String getContextPath() {
        return servletContext.getContextPath();
    }

    @Override
    public String getQueryString() {
        return exchange.getQueryString().isEmpty() ? null : exchange.getQueryString();
    }

    @Override
    public String getRemoteUser() {
        Principal userPrincipal = getUserPrincipal();

        return userPrincipal != null ? userPrincipal.getName() : null;
    }

    @Override
    public boolean isUserInRole(final String role) {
        SecurityContext sc = exchange.getAttachment(SecurityContext.ATTACHMENT_KEY);
        Account account = sc.getAuthenticatedAccount();
        if (account == null) {
            return false;
        }

        final ServletChain servlet = exchange.getAttachment(ServletRequestContext.ATTACHMENT_KEY).getCurrentServlet();

        final Set<String> roles = servletContext.getDeployment().getDeploymentInfo().getPrincipalVersusRolesMap().get(account.getPrincipal().getName());
        //TODO: a more efficient imple
        for (SecurityRoleRef ref : servlet.getManagedServlet().getServletInfo().getSecurityRoleRefs()) {
            if (ref.getRole().equals(role)) {
                if (roles != null && roles.contains(ref.getLinkedRole())) {
                    return true;
                }
                return account.getRoles().contains(ref.getLinkedRole());
            }
        }
        if (roles != null && roles.contains(role)) {
            return true;
        }
        return account.getRoles().contains(role);
    }

    @Override
    public Principal getUserPrincipal() {
        SecurityContext securityContext = exchange.getAttachment(SecurityContext.ATTACHMENT_KEY);
        Principal result = null;
        Account account = null;
        if (securityContext != null && (account = securityContext.getAuthenticatedAccount()) != null) {
            result = account.getPrincipal();
        }
        return result;
    }

    @Override
    public String getRequestedSessionId() {
        SessionConfig config = originalServletContext.getSessionConfig();
        return config.findSessionId(exchange);
    }

    @Override
    public String changeSessionId() {
        HttpSessionImpl session = originalServletContext.getSession(exchange, false);
        if (session == null) {
            throw UndertowServletMessages.MESSAGES.noSession();
        }
        String oldId = session.getId();
        String newId = session.getSession().changeSessionId(exchange, originalServletContext.getSessionCookieConfig());
        originalServletContext.getDeployment().getApplicationListeners().httpSessionIdChanged(session, oldId);
        return newId;
    }

    @Override
    public String getRequestURI() {
        //we need the non-decoded string, which means we need to use exchange.getRequestURI()
        if(exchange.isHostIncludedInRequestURI()) {
            //we need to strip out the host part
            String uri = exchange.getRequestURI();
            int slashes =0;
            for(int i = 0; i < uri.length(); ++i) {
                if(uri.charAt(i) == '/') {
                    if(++slashes == 3) {
                        return uri.substring(i);
                    }
                }
            }
            return "/";
        } else {
            return exchange.getRequestURI();
        }
    }

    @Override
    public StringBuffer getRequestURL() {
        return new StringBuffer(exchange.getRequestURL());
    }

    @Override
    public String getServletPath() {
        ServletPathMatch match = exchange.getAttachment(ServletRequestContext.ATTACHMENT_KEY).getServletPathMatch();
        if (match != null) {
            return match.getMatched();
        }
        return "";
    }

    @Override
    public HttpSession getSession(final boolean create) {
        return originalServletContext.getSession(exchange, create);
    }

    @Override
    public HttpSession getSession() {
        return getSession(true);
    }


    @Override
    public boolean isRequestedSessionIdValid() {
        HttpSessionImpl session = originalServletContext.getSession(exchange, false);
        return session != null;
    }

    @Override
    public boolean isRequestedSessionIdFromCookie() {
        return sessionCookieSource() == SessionConfig.SessionCookieSource.COOKIE;
    }

    @Override
    public boolean isRequestedSessionIdFromURL() {
        return sessionCookieSource() == SessionConfig.SessionCookieSource.URL;
    }

    @Override
    public boolean isRequestedSessionIdFromUrl() {
        return isRequestedSessionIdFromURL();
    }

    @Override
    public boolean authenticate(final HttpServletResponse response) throws IOException, ServletException {
        if (response.isCommitted()) {
            throw UndertowServletMessages.MESSAGES.responseAlreadyCommited();
        }

        SecurityContext sc = exchange.getAttachment(SecurityContext.ATTACHMENT_KEY);
        sc.setAuthenticationRequired();
        // TODO: this will set the status code and headers without going through any potential
        // wrappers, is this a problem?
        if (sc.authenticate()) {
            if (sc.isAuthenticated()) {
                return true;
            } else {
                throw UndertowServletMessages.MESSAGES.authenticationFailed();
            }
        } else {
            // Not authenticated and response already sent.
            HttpServletResponseImpl responseImpl = exchange.getAttachment(ServletRequestContext.ATTACHMENT_KEY).getOriginalResponse();
            responseImpl.closeStreamAndWriter();
            return false;
        }
    }

    @Override
    public void login(final String username, final String password) throws ServletException {
        if (username == null || password == null) {
            throw UndertowServletMessages.MESSAGES.loginFailed();
        }
        SecurityContext sc = exchange.getAttachment(SecurityContext.ATTACHMENT_KEY);
        if (sc.isAuthenticated()) {
            throw UndertowServletMessages.MESSAGES.userAlreadyLoggedIn();
        }
        if (!sc.login(username, password)) {
            throw UndertowServletMessages.MESSAGES.loginFailed();
        }
    }

    @Override
    public void logout() throws ServletException {
        SecurityContext sc = exchange.getAttachment(SecurityContext.ATTACHMENT_KEY);
        sc.logout();
        if(servletContext.getDeployment().getDeploymentInfo().isInvalidateSessionOnLogout()) {
            HttpSession session = getSession(false);
            if(session != null) {
                session.invalidate();
            }
        }
    }

    @Override
    public Collection<Part> getParts() throws IOException, ServletException {
        if (parts == null) {
            loadParts();
        }
        return parts;
    }

    @Override
    public Part getPart(final String name) throws IOException, ServletException {
        if (parts == null) {
            loadParts();
        }
        for (Part part : parts) {
            if (part.getName().equals(name)) {
                return part;
            }
        }
        return null;
    }

    @Override
    public <T extends HttpUpgradeHandler> T upgrade(final Class<T> handlerClass) throws IOException {
        try {
            InstanceFactory<T> factory = servletContext.getDeployment().getDeploymentInfo().getClassIntrospecter().createInstanceFactory(handlerClass);
            final InstanceHandle<T> instance = factory.createInstance();
            exchange.upgradeChannel(new ServletUpgradeListener<T>(instance, servletContext.getDeployment().getThreadSetupAction()));
            return instance.getInstance();
        } catch (InstantiationException e) {
            throw new RuntimeException(e);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    private void loadParts() throws IOException, ServletException {
        final ServletRequestContext requestContext = exchange.getAttachment(ServletRequestContext.ATTACHMENT_KEY);
        readStarted = true;
        if (parts == null) {
            final List<Part> parts = new ArrayList<Part>();
            String mimeType = exchange.getRequestHeaders().getFirst(Headers.CONTENT_TYPE);
            if (mimeType != null && mimeType.startsWith(MultiPartParserDefinition.MULTIPART_FORM_DATA)) {
                final ManagedServlet originalServlet = exchange.getAttachment(ServletRequestContext.ATTACHMENT_KEY).getOriginalServletPathMatch().getServletChain().getManagedServlet();
                final FormDataParser parser = originalServlet.getFormParserFactory().createParser(exchange);
                if(parser != null) {
                    final FormData value = parser.parseBlocking();
                    for (final String namedPart : value) {
                        for (FormData.FormValue part : value.get(namedPart)) {
                            parts.add(new PartImpl(namedPart, part, requestContext.getOriginalServletPathMatch().getServletChain().getManagedServlet().getServletInfo().getMultipartConfig(), servletContext));
                        }
                    }
                }
            } else {
                throw UndertowServletMessages.MESSAGES.notAMultiPartRequest();
            }
            this.parts = parts;
        }
    }

    @Override
    public Object getAttribute(final String name) {
        if (attributes == null) {
            return null;
        }
        return attributes.get(name);
    }

    @Override
    public Enumeration<String> getAttributeNames() {
        if (attributes == null) {
            return EmptyEnumeration.instance();
        }
        return new IteratorEnumeration<String>(attributes.keySet().iterator());
    }

    @Override
    public String getCharacterEncoding() {
        if (characterEncoding != null) {
            return characterEncoding.name();
        }
        String contentType = exchange.getRequestHeaders().getFirst(Headers.CONTENT_TYPE);
        if (contentType == null) {
            return null;
        }
        return Headers.extractTokenFromHeader(contentType, "charset");
    }

    @Override
    public void setCharacterEncoding(final String env) throws UnsupportedEncodingException {
        if (readStarted) {
            return;
        }
        try {
            characterEncoding = Charset.forName(env);

            final ManagedServlet originalServlet = exchange.getAttachment(ServletRequestContext.ATTACHMENT_KEY).getOriginalServletPathMatch().getServletChain().getManagedServlet();
            final FormDataParser parser = originalServlet.getFormParserFactory().createParser(exchange);
            if (parser != null) {
                parser.setCharacterEncoding(env);
            }
        } catch (UnsupportedCharsetException e) {
            throw new UnsupportedEncodingException();
        }
    }

    @Override
    public int getContentLength() {
        final String contentLength = getHeader(Headers.CONTENT_LENGTH);
        if (contentLength == null || contentLength.isEmpty()) {
            return -1;
        }
        return Integer.parseInt(contentLength);
    }

    @Override
    public long getContentLengthLong() {
        final String contentLength = getHeader(Headers.CONTENT_LENGTH);
        if (contentLength == null || contentLength.isEmpty()) {
            return -1;
        }
        return Long.parseLong(contentLength);
    }

    @Override
    public String getContentType() {
        return getHeader(Headers.CONTENT_TYPE);
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        if (reader != null) {
            throw UndertowServletMessages.MESSAGES.getReaderAlreadyCalled();
        }
        if(servletInputStream == null) {
            servletInputStream = new ServletInputStreamImpl(this);
        }
        readStarted = true;
        return servletInputStream;
    }

    public void closeAndDrainRequest() throws IOException {
        if(reader != null) {
            reader.close();
        }
        if(servletInputStream == null) {
            servletInputStream = new ServletInputStreamImpl(this);
        }
        servletInputStream.close();
    }

    @Override
    public String getParameter(final String name) {
        if(queryParameters == null) {
            queryParameters = exchange.getQueryParameters();
        }
        Deque<String> params = queryParameters.get(name);
        if (params == null) {
            if (exchange.getRequestMethod().equals(Methods.POST)) {
                final FormData parsedFormData = parseFormData();
                if (parsedFormData != null) {
                    FormData.FormValue res = parsedFormData.getFirst(name);
                    if (res == null || res.isFile()) {
                        return null;
                    } else {
                        return res.getValue();
                    }
                }
            }
            return null;
        }
            return params.getFirst();
    }

    @Override
    public Enumeration<String> getParameterNames() {
        if (queryParameters == null) {
            queryParameters = exchange.getQueryParameters();
        }
        final Set<String> parameterNames = new HashSet<String>(queryParameters.keySet());
        if (exchange.getRequestMethod().equals(Methods.POST)) {
            final FormData parsedFormData = parseFormData();
            if (parsedFormData != null) {
                Iterator<String> it = parsedFormData.iterator();
                while (it.hasNext()) {
                    String name = it.next();
                    for(FormData.FormValue param : parsedFormData.get(name)) {
                        if(!param.isFile()) {
                            parameterNames.add(name);
                            break;
                        }
                    }
                }
            }
        }
        return new IteratorEnumeration<String>(parameterNames.iterator());
    }

    @Override
    public String[] getParameterValues(final String name) {
        if (queryParameters == null) {
            queryParameters = exchange.getQueryParameters();
        }
        final List<String> ret = new ArrayList<String>();
        Deque<String> params = queryParameters.get(name);
        if (params != null) {
            for (String param : params) {
                ret.add(param);
            }
        }
        if (exchange.getRequestMethod().equals(Methods.POST)) {
            final FormData parsedFormData = parseFormData();
            if (parsedFormData != null) {
                Deque<FormData.FormValue> res = parsedFormData.get(name);
                if (res != null) {
                    for (FormData.FormValue value : res) {
                        if(!value.isFile()) {
                            ret.add(value.getValue());
                        }
                    }
                }
            }
        }
        if (ret.isEmpty()) {
            return null;
        }
        return ret.toArray(new String[ret.size()]);
    }

    @Override
    public Map<String, String[]> getParameterMap() {
        if (queryParameters == null) {
            queryParameters = exchange.getQueryParameters();
        }
        final Map<String, ArrayList<String>> arrayMap = new HashMap<String, ArrayList<String>>();
        for (Map.Entry<String, Deque<String>> entry : queryParameters.entrySet()) {
            arrayMap.put(entry.getKey(), new ArrayList<String>(entry.getValue()));
        }
        if (exchange.getRequestMethod().equals(Methods.POST)) {

            final FormData parsedFormData = parseFormData();
            if (parsedFormData != null) {
                Iterator<String> it = parsedFormData.iterator();
                while (it.hasNext()) {
                    final String name = it.next();
                    Deque<FormData.FormValue> val = parsedFormData.get(name);
                    if (arrayMap.containsKey(name)) {
                        ArrayList<String> existing = arrayMap.get(name);
                        for (final FormData.FormValue v : val) {
                            if(!v.isFile()) {
                                existing.add(v.getValue());
                            }
                        }
                    } else {
                        final ArrayList<String> values = new ArrayList<String>();
                        int i = 0;
                        for (final FormData.FormValue v : val) {
                            if(!v.isFile()) {
                                values.add(v.getValue());
                            }
                        }
                        arrayMap.put(name, values);
                    }
                }
            }
        }
        final Map<String, String[]> ret = new HashMap<String, String[]>();
        for(Map.Entry<String, ArrayList<String>> entry : arrayMap.entrySet()) {
            ret.put(entry.getKey(), entry.getValue().toArray(new String[entry.getValue().size()]));
        }
        return ret;
    }

    private FormData parseFormData() {
        if (parsedFormData == null) {
            if (readStarted) {
                return null;
            }
            readStarted = true;
            final ManagedServlet originalServlet = exchange.getAttachment(ServletRequestContext.ATTACHMENT_KEY).getOriginalServletPathMatch().getServletChain().getManagedServlet();
            final FormDataParser parser = originalServlet.getFormParserFactory().createParser(exchange);
            if (parser == null) {
                return null;
            }
            try {
                return parsedFormData = parser.parseBlocking();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return parsedFormData;
    }

    @Override
    public String getProtocol() {
        return exchange.getProtocol().toString();
    }

    @Override
    public String getScheme() {
        return exchange.getRequestScheme();
    }

    @Override
    public String getServerName() {
        return exchange.getHostName();
    }

    @Override
    public int getServerPort() {
        return exchange.getHostPort();
    }

    @Override
    public BufferedReader getReader() throws IOException {
        if (reader == null) {
            if (servletInputStream != null) {
                throw UndertowServletMessages.MESSAGES.getInputStreamAlreadyCalled();
            }
            Charset charSet = DEFAULT_CHARSET;
            if (characterEncoding != null) {
                charSet = characterEncoding;
            } else {
                String contentType = exchange.getRequestHeaders().getFirst(Headers.CONTENT_TYPE);
                if (contentType != null) {
                    String c = Headers.extractTokenFromHeader(contentType, "charset");
                    if (c != null) {
                        try {
                            charSet = Charset.forName(c);
                        } catch (UnsupportedCharsetException e) {
                            throw new UnsupportedEncodingException();
                        }
                    }
                }
            }

            reader = new BufferedReader(new InputStreamReader(exchange.getInputStream(), charSet));
        }
        readStarted = true;
        return reader;
    }

    @Override
    public String getRemoteAddr() {
        InetSocketAddress sourceAddress = exchange.getSourceAddress();
        if(sourceAddress == null) {
            return "";
        }
        InetAddress address = sourceAddress.getAddress();
        if(address == null) {
            //this is unresolved, so we just return the host name
            //not exactly spec, but if the name should be resolved then a PeerNameResolvingHandler should be used
            //and this is probably better than just returning null
            return sourceAddress.getHostString();
        }
        return address.getHostAddress();
    }

    @Override
    public String getRemoteHost() {
        InetSocketAddress sourceAddress = exchange.getSourceAddress();
        if(sourceAddress == null) {
            return "";
        }
        return sourceAddress.getHostString();
    }

    @Override
    public void setAttribute(final String name, final Object object) {
        if (attributes == null) {
            attributes = new HashMap<String, Object>();
        }
        Object existing = attributes.put(name, object);
        if (existing != null) {
            servletContext.getDeployment().getApplicationListeners().servletRequestAttributeReplaced(this, name, existing);
        } else {
            servletContext.getDeployment().getApplicationListeners().servletRequestAttributeAdded(this, name, object);
        }
    }

    @Override
    public void removeAttribute(final String name) {
        if (attributes == null) {
            return;
        }
        Object exiting = attributes.remove(name);
        servletContext.getDeployment().getApplicationListeners().servletRequestAttributeRemoved(this, name, exiting);
    }

    @Override
    public Locale getLocale() {
        return getLocales().nextElement();
    }

    @Override
    public Enumeration<Locale> getLocales() {
        final List<String> acceptLanguage = exchange.getRequestHeaders().get(Headers.ACCEPT_LANGUAGE);
        List<Locale> ret = LocaleUtils.getLocalesFromHeader(acceptLanguage);
        return new IteratorEnumeration<Locale>(ret.iterator());
    }

    @Override
    public boolean isSecure() {
        return getAttribute("javax.servlet.request.ssl_session_id") != null;//todo this could be done better
    }

    @Override
    public RequestDispatcher getRequestDispatcher(final String path) {
        String realPath;
        if (path.startsWith("/")) {
            realPath = path;
        } else {
            String current = exchange.getRelativePath();
            int lastSlash = current.lastIndexOf("/");
            if (lastSlash != -1) {
                current = current.substring(0, lastSlash + 1);
            }
            realPath = CanonicalPathUtils.canonicalize(current + path);
        }
        return new RequestDispatcherImpl(realPath, servletContext);
    }

    @Override
    public String getRealPath(final String path) {
        return servletContext.getRealPath(path);
    }

    @Override
    public int getRemotePort() {
        return exchange.getSourceAddress().getPort();
    }

    @Override
    public String getLocalName() {
        return exchange.getDestinationAddress().getHostName();
    }

    @Override
    public String getLocalAddr() {
        SocketAddress address = exchange.getConnection().getLocalAddress();
         if (address instanceof InetSocketAddress) {
            return ((InetSocketAddress) address).getAddress().getHostAddress();
        } else if (address instanceof LocalSocketAddress) {
            return ((LocalSocketAddress) address).getName();
        }
        return null;
    }

    @Override
    public int getLocalPort() {
        SocketAddress address = exchange.getConnection().getLocalAddress();
        if (address instanceof InetSocketAddress) {
            return ((InetSocketAddress) address).getPort();
        }
        return -1;
    }

    @Override
    public ServletContextImpl getServletContext() {
        return servletContext;
    }

    @Override
    public AsyncContext startAsync() throws IllegalStateException {
        if (!isAsyncSupported()) {
            throw UndertowServletMessages.MESSAGES.startAsyncNotAllowed();
        } else if (asyncStarted) {
            throw UndertowServletMessages.MESSAGES.asyncAlreadyStarted();
        }
        asyncStarted = true;
        final ServletRequestContext servletRequestContext = exchange.getAttachment(ServletRequestContext.ATTACHMENT_KEY);
        return asyncContext = new AsyncContextImpl(exchange, servletRequestContext.getServletRequest(), servletRequestContext.getServletResponse(), servletRequestContext, false, asyncContext);
    }

    @Override
    public AsyncContext startAsync(final ServletRequest servletRequest, final ServletResponse servletResponse) throws IllegalStateException {
        final ServletRequestContext servletRequestContext = exchange.getAttachment(ServletRequestContext.ATTACHMENT_KEY);
        if (!servletContext.getDeployment().getDeploymentInfo().isAllowNonStandardWrappers()) {
            if (servletRequestContext.getOriginalRequest() != servletRequest) {
                if (!(servletRequest instanceof ServletRequestWrapper)) {
                    throw UndertowServletMessages.MESSAGES.requestWasNotOriginalOrWrapper(servletRequest);
                }
            }
            if (servletRequestContext.getOriginalResponse() != servletResponse) {
                if (!(servletResponse instanceof ServletResponseWrapper)) {
                    throw UndertowServletMessages.MESSAGES.responseWasNotOriginalOrWrapper(servletResponse);
                }
            }
        }
        if (!isAsyncSupported()) {
            throw UndertowServletMessages.MESSAGES.startAsyncNotAllowed();
        } else if (asyncStarted) {
            throw UndertowServletMessages.MESSAGES.asyncAlreadyStarted();
        }
        asyncStarted = true;
        return asyncContext = new AsyncContextImpl(exchange, servletRequest, servletResponse, servletRequestContext, true, asyncContext);
    }

    @Override
    public boolean isAsyncStarted() {
        return asyncStarted;
    }

    @Override
    public boolean isAsyncSupported() {
        Boolean supported = exchange.getAttachment(AsyncContextImpl.ASYNC_SUPPORTED);
        return supported == null || supported;
    }

    @Override
    public AsyncContextImpl getAsyncContext() {
        if (!asyncStarted) {
            throw UndertowServletMessages.MESSAGES.asyncNotStarted();
        }
        return asyncContext;
    }

    public AsyncContextImpl getAsyncContextInternal() {
        return asyncContext;
    }

    @Override
    public DispatcherType getDispatcherType() {
        return exchange.getAttachment(ServletRequestContext.ATTACHMENT_KEY).getDispatcherType();
    }


    public Map<String, Deque<String>> getQueryParameters() {
        if (queryParameters == null) {
            queryParameters = exchange.getQueryParameters();
        }
        return queryParameters;
    }

    public void setQueryParameters(final Map<String, Deque<String>> queryParameters) {
        this.queryParameters = queryParameters;
    }

    public void setServletContext(final ServletContextImpl servletContext) {
        this.servletContext = servletContext;
    }

    void asyncRequestDispatched() {
        asyncStarted = false;
    }

    public String getOriginalRequestURI() {
        String uri = (String) getAttribute(RequestDispatcher.FORWARD_REQUEST_URI);
        if(uri != null) {
            return uri;
        }
        uri = (String) getAttribute(AsyncContext.ASYNC_REQUEST_URI);
        if(uri != null) {
            return uri;
        }
        return getRequestURI();
    }


    public String getOriginalServletPath() {
        String uri = (String) getAttribute(RequestDispatcher.FORWARD_SERVLET_PATH);
        if(uri != null) {
            return uri;
        }
        uri = (String) getAttribute(AsyncContext.ASYNC_SERVLET_PATH);
        if(uri != null) {
            return uri;
        }
        return getServletPath();
    }

    public String getOriginalPathInfo() {
        String uri = (String) getAttribute(RequestDispatcher.FORWARD_PATH_INFO);
        if(uri != null) {
            return uri;
        }
        uri = (String) getAttribute(AsyncContext.ASYNC_PATH_INFO);
        if(uri != null) {
            return uri;
        }
        return getPathInfo();
    }

    public String getOriginalContextPath() {
        String uri = (String) getAttribute(RequestDispatcher.FORWARD_CONTEXT_PATH);
        if(uri != null) {
            return uri;
        }
        uri = (String) getAttribute(AsyncContext.ASYNC_CONTEXT_PATH);
        if(uri != null) {
            return uri;
        }
        return getContextPath();
    }

    public String getOriginalQueryString() {
        String uri = (String) getAttribute(RequestDispatcher.FORWARD_QUERY_STRING);
        if(uri != null) {
            return uri;
        }
        uri = (String) getAttribute(AsyncContext.ASYNC_QUERY_STRING);
        if(uri != null) {
            return uri;
        }
        return getQueryString();
    }

    private SessionConfig.SessionCookieSource sessionCookieSource() {
        if(sessionCookieSource == null) {
            sessionCookieSource = originalServletContext.getSessionCookieConfig().sessionCookieSource(exchange);
        }
        return sessionCookieSource;
    }
}
