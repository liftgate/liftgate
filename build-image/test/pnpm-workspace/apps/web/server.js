const greeting = require("node:fs").readFileSync(`${__dirname}/greeting.txt`);

require("node:http")
  .createServer((request, response) => response.end(greeting))
  .listen(process.env.PORT);
