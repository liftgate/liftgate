require("node:http")
  .createServer((request, response) => response.end("hello from liftgate\n"))
  .listen(process.env.PORT);
