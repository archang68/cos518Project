import sys
from subprocess import Popen

MAX_CPU_ID = 15

config_file = sys.argv[1]
node_configs = [line.rstrip('\n') for line in open(config_file) if not line.startswith('//')]

ports = [int(config.split(':')[1]) for config in node_configs]

if len(ports) > MAX_CPU_ID:
    print('too many nodes for only {} cores'.format(MAX_CPU_ID))
    exit(-1)

nodes = []

output = sys.stdout

print("Starting server nodes...")

for i, port in enumerate(ports):
    current_core_mask = hex(1 << i)

    if i == 0:
        nodes.append(Popen([
            'taskset',
            '{CORE_MASK}'.format(CORE_MASK=current_core_mask),
            'java',
            '-jar',
            'ChainNode/target/chain-node-dev-jar-with-dependencies.jar',
            str(port),
            'head',
            'localhost:{SUCCESSOR_PORT}'.format(SUCCESSOR_PORT=ports[i])
        ], stdout=output, stderr=output))
    elif i == (len(ports) - 1):
        nodes.append(Popen([
            'taskset',
            '{CORE_MASK}'.format(CORE_MASK=current_core_mask),
            'java',
            '-jar',
            'ChainNode/target/chain-node-dev-jar-with-dependencies.jar',
            str(port),
            'tail'
        ], stdout=output, stderr=output))
    else:
        nodes.append(Popen([
            'taskset',
            '{CORE_MASK}'.format(CORE_MASK=current_core_mask),
            'java',
            '-jar',
            'ChainNode/target/chain-node-dev-jar-with-dependencies.jar',
            str(port),
            'middle',
            'localhost:{SUCCESSOR_PORT}'.format(SUCCESSOR_PORT=ports[i])
        ], stdout=output, stderr=output))

master = Popen([
    'taskset',
    '{CORE_MASK}'.format(CORE_MASK=hex(1 << MAX_CPU_ID)),
    'java',
    '-jar',
    'ChainMaster/target/chain-master-dev-jar-with-dependencies.jar',
    str(9000),
    config_file
])

while input('Press enter to kill nodes'):
    print('non-enter key pressed')

print('killing nodes')
master.kill()
for node in nodes:
    node.kill()
