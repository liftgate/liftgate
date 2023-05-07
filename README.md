<img src=".github/resources/profile.png" width="25%" margin-right="1000px" align="right">

# Liftgate
All-in-one game server service mesh, autoscaling, and resource management platform.

## Project Status
**This project is a W.I.P.** This README will be updated once we enter a more stable state.

## Notice
Kubernetes (autoscaling), a service mesh like Istio or HashiCorp's Consul, and a GitOps solution like Flux or Argo CD is a much better solution than whatever is offered here.

This should only be used for smaller, single bare-metal server setups where using the systems above would act as a disadvantage in an environment where resources are limited and carefully used.

## Platform Compatability 
The liftgate server project is only built to support Unix-based operating systems. The project has also only been tested on Ubuntu 20.04.

The liftgate server also only works on single-server systems. Compatibility for multi-server systems will not be introduced in the future.

### TODO
- [ ] Use hoplite for parsing/saving configuration files on the server and other platforms
- [ ] GitOps-like deployment for "resources" and server replica templates
