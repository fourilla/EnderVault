const MAX_COMMAND_LENGTH = 65_536;
const MAX_HEADER_COUNT = 64;
const MAX_HEADER_NAME_LENGTH = 128;
const MAX_HEADER_VALUE_LENGTH = 8_192;
const MAX_TOTAL_HEADER_LENGTH = 32_768;
const HEADER_NAME = /^[!#$%&'*+.^_`|~0-9A-Za-z-]+$/;
const IGNORED_OPTIONS = new Set(['-L', '--location', '--compressed', '-s', '--silent', '-S', '--show-error']);

const clean = (value: unknown) => String(value ?? '').trim();

function tokenize(command: string) {
  const tokens: string[] = [];
  let token = '';
  let tokenStarted = false;
  let singleQuoted = false;
  let doubleQuoted = false;
  let escaping = false;

  for (let index = 0; index < command.length; index += 1) {
    const current = command[index];
    if (escaping) {
      if (current === '\r' && command[index + 1] === '\n') index += 1;
      else if (current !== '\n' && current !== '\r') {
        token += current;
        tokenStarted = true;
      }
      escaping = false;
      continue;
    }
    if (current === '\\' && !singleQuoted) {
      escaping = true;
      continue;
    }
    if (current === "'" && !doubleQuoted) {
      singleQuoted = !singleQuoted;
      tokenStarted = true;
      continue;
    }
    if (current === '"' && !singleQuoted) {
      doubleQuoted = !doubleQuoted;
      tokenStarted = true;
      continue;
    }
    if (/\s/.test(current) && !singleQuoted && !doubleQuoted) {
      if (tokenStarted) {
        tokens.push(token);
        token = '';
        tokenStarted = false;
      }
      continue;
    }
    token += current;
    tokenStarted = true;
  }
  if (escaping || singleQuoted || doubleQuoted) throw new Error('The cURL command contains an unfinished quote or escape.');
  if (tokenStarted) tokens.push(token);
  return tokens;
}

const isCurlExecutable = (value: string) => {
  const normalized = value.replaceAll('\\', '/');
  const executable = normalized.substring(normalized.lastIndexOf('/') + 1).toLowerCase();
  return executable === 'curl' || executable === 'curl.exe';
};

export function parseCurl(rawCommand: string) {
  const command = clean(rawCommand);
  if (!command) throw new Error('Paste a cURL command first.');
  if (command.length > MAX_COMMAND_LENGTH) throw new Error('The cURL command is too long.');
  const tokens = tokenize(command);
  if (!tokens.length || !isCurlExecutable(tokens[0])) throw new Error('Only a cURL command can be imported.');

  let url = '';
  const headers: Array<{ name: string; value: string }> = [];
  const headerNames = new Set<string>();
  const requiredValue = (index: number, option: string) => {
    if (index >= tokens.length) throw new Error(`cURL option requires a value: ${option}`);
    return tokens[index];
  };
  const setUrl = (candidate: string) => {
    const value = clean(candidate);
    if (!value) throw new Error('The cURL URL is empty.');
    if (url) throw new Error('The cURL command must contain exactly one URL.');
    url = value;
  };
  const addHeader = (rawName: string, rawValue: string) => {
    const name = clean(rawName);
    const value = clean(rawValue);
    const normalizedName = name.toLowerCase();
    if (!HEADER_NAME.test(name) || name.length > MAX_HEADER_NAME_LENGTH) throw new Error('The cURL command contains an invalid header name.');
    if (!value || value.length > MAX_HEADER_VALUE_LENGTH || /[\r\n]/.test(value)) throw new Error(`The cURL command contains an invalid header value: ${name}`);
    if (headerNames.has(normalizedName)) throw new Error(`The cURL command contains a duplicated header: ${name}`);
    headerNames.add(normalizedName);
    headers.push({ name, value });
  };
  const addHeaderLine = (line: string) => {
    const separator = line.indexOf(':');
    if (separator <= 0) throw new Error('cURL headers must use the Name: value format.');
    addHeader(line.substring(0, separator), line.substring(separator + 1));
  };

  for (let index = 1; index < tokens.length; index += 1) {
    const token = tokens[index];
    if (token === '--url') setUrl(requiredValue(++index, token));
    else if (token.startsWith('--url=')) setUrl(token.substring('--url='.length));
    else if (token === '-H' || token === '--header') addHeaderLine(requiredValue(++index, token));
    else if (token.startsWith('--header=')) addHeaderLine(token.substring('--header='.length));
    else if (token === '-b' || token === '--cookie') {
      const cookie = requiredValue(++index, token);
      if (cookie.startsWith('@')) throw new Error('Cookie files are not supported in cURL imports.');
      addHeader('Cookie', cookie);
    } else if (token.startsWith('--cookie=')) {
      const cookie = token.substring('--cookie='.length);
      if (cookie.startsWith('@')) throw new Error('Cookie files are not supported in cURL imports.');
      addHeader('Cookie', cookie);
    } else if (token === '-A' || token === '--user-agent') addHeader('User-Agent', requiredValue(++index, token));
    else if (token.startsWith('--user-agent=')) addHeader('User-Agent', token.substring('--user-agent='.length));
    else if (token === '-e' || token === '--referer') addHeader('Referer', requiredValue(++index, token));
    else if (token.startsWith('--referer=')) addHeader('Referer', token.substring('--referer='.length));
    else if (token === '-X' || token === '--request') {
      if (clean(requiredValue(++index, token)).toUpperCase() !== 'GET') throw new Error('Only GET cURL requests can be imported.');
    } else if (token.startsWith('--request=')) {
      if (clean(token.substring('--request='.length)).toUpperCase() !== 'GET') throw new Error('Only GET cURL requests can be imported.');
    } else if (IGNORED_OPTIONS.has(token)) {
      // EnderVault owns redirects and response decoding.
    } else if (token.startsWith('-')) throw new Error(`Unsupported cURL option: ${token}`);
    else setUrl(token);
  }

  if (!url) throw new Error('The cURL command does not contain a URL.');
  try {
    const parsedUrl = new URL(url);
    if (parsedUrl.protocol !== 'http:' && parsedUrl.protocol !== 'https:') throw new Error();
  } catch {
    throw new Error('The cURL command contains an invalid HTTP URL.');
  }
  if (headers.length > MAX_HEADER_COUNT) throw new Error('The cURL command contains too many headers.');
  if (headers.reduce((total, header) => total + header.name.length + header.value.length, 0) > MAX_TOTAL_HEADER_LENGTH) {
    throw new Error('The cURL headers are too large.');
  }
  return {
    url,
    customHeaders: headers.map((header) => `${header.name}: ${header.value}`).join('\n'),
  };
}
