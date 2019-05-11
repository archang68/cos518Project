from datetime import datetime
from subprocess import Popen

WORKLOADS = range(0, 105, 5)
NUM_CLIENTS = 5
REQUESTS_PER_CLIENT = 10000

output = open('/dev/null')

for workload in reversed(WORKLOADS):
    clients = []
    start_times = []
    end_times = []
    client_times = []

    for i in range(NUM_CLIENTS):
        current_core_mask = hex(0x1 << (14 - i))

        command = [
            'taskset',
            current_core_mask,
            'java',
            '-jar',
            'ChainClient/target/chain-client-dev-jar-with-dependencies.jar',
            str(REQUESTS_PER_CLIENT),
            str(workload),
            str(19*(i+1))
        ]

        start_times.append(datetime.utcnow())
        end_times.append(datetime.utcnow())
        client_times.append(datetime.utcnow())
        client = Popen(command, stdout=output, stderr=output)
        clients.append(client)

    still_running = True
    while still_running:
        still_running = False
        for i, client in enumerate(clients):
            if client.poll() is not None:
                end_times[i] = datetime.utcnow()
            else:
                still_running = True

    for i, start_time in enumerate(start_times):
        end_time = end_times[i]
        client_times[i] = str(int((end_time - start_time).total_seconds() * 1000))

    print('{WORKLOAD},{CLIENT_TIMES}'.format(WORKLOAD=workload, CLIENT_TIMES=','.join(client_times)))
