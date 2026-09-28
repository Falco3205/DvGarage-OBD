const SYSTEMS = { P: 'Powertrain', C: 'Chassis', B: 'Body', U: 'Network' };

const KNOWN = {
  P0100: 'Mass or volume air flow circuit malfunction',
  P0101: 'Mass air flow circuit range/performance',
  P0102: 'Mass air flow circuit low input',
  P0103: 'Mass air flow circuit high input',
  P0110: 'Intake air temperature circuit malfunction',
  P0115: 'Engine coolant temperature circuit malfunction',
  P0117: 'Engine coolant temperature circuit low input',
  P0118: 'Engine coolant temperature circuit high input',
  P0120: 'Throttle/pedal position sensor A circuit malfunction',
  P0130: 'O2 sensor circuit malfunction (bank 1 sensor 1)',
  P0171: 'System too lean (bank 1)',
  P0172: 'System too rich (bank 1)',
  P0174: 'System too lean (bank 2)',
  P0175: 'System too rich (bank 2)',
  P0300: 'Random/multiple cylinder misfire detected',
  P0301: 'Cylinder 1 misfire detected',
  P0302: 'Cylinder 2 misfire detected',
  P0303: 'Cylinder 3 misfire detected',
  P0304: 'Cylinder 4 misfire detected',
  P0335: 'Crankshaft position sensor A circuit malfunction',
  P0340: 'Camshaft position sensor circuit malfunction',
  P0400: 'Exhaust gas recirculation flow malfunction',
  P0401: 'Exhaust gas recirculation flow insufficient',
  P0420: 'Catalyst system efficiency below threshold (bank 1)',
  P0430: 'Catalyst system efficiency below threshold (bank 2)',
  P0440: 'Evaporative emission control system malfunction',
  P0442: 'Evaporative emission system leak detected (small)',
  P0455: 'Evaporative emission system leak detected (large)',
  P0500: 'Vehicle speed sensor malfunction',
  P0505: 'Idle control system malfunction',
  P0562: 'System voltage low',
  P0563: 'System voltage high',
  P0600: 'Serial communication link malfunction',
  P0700: 'Transmission control system malfunction',
  U0100: 'Lost communication with ECM/PCM',
};

const CODE_PATTERN = /^[PCBU][0-3][0-9A-F]{3}$/;

export function isValidDtc(code) {
  return typeof code === 'string' && CODE_PATTERN.test(code);
}

export function describeDtc(code) {
  return {
    code,
    system: SYSTEMS[code[0]] ?? 'Unknown',
    generic: code[1] === '0' || code[1] === '2',
    description: KNOWN[code] ?? null,
  };
}
