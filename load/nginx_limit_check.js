// D-035 verification: prove the BROWSE_READ ceiling is enforced at nginx.
//
// Sized deliberately: a small VU pool keeps responses fast so the offered rate can actually reach
// nginx's 500 r/s browse ceiling. An earlier attempt with 1,500 VUs against a 2-core container
// produced 4-second responses, collapsing the achieved rate to 296 r/s — below the threshold under
// test, so nginx never engaged and the run measured Tomcat queueing instead.
import http from 'k6/http';
import { Counter } from 'k6/metrics';

const nginxShed = new Counter('shed_by_nginx');
const appShed = new Counter('shed_by_app');
const served = new Counter('served_200');

export const options = {
    scenarios: {
        browse_flood: {
            executor: 'constant-arrival-rate',
            rate: 1500,
            timeUnit: '1s',
            duration: '15s',
            preAllocatedVUs: 80,
            maxVUs: 200,
        },
    },
};

export default function () {
    const res = http.get('http://localhost:8080/v1/browse/popular-now');
    if (res.status === 200) {
        served.add(1);
    } else if (res.status === 429) {
        if (res.body && res.body.indexOf('nginx') !== -1) {
            nginxShed.add(1);
        } else {
            appShed.add(1);
        }
    }
}
