mvn clean install package
eval $(aws ecr get-login --no-include-email --region us-east-1)
docker build -f Dockerfile -t 339267708833.dkr.ecr.us-east-1.amazonaws.com/prebid:v1.0.1 .
docker push 339267708833.dkr.ecr.us-east-1.amazonaws.com/prebid:v1.0.1
